package org.swisspush.gateleen.hook.reducedpropagation;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.HttpRequestHeader;
import org.swisspush.gateleen.queue.queuing.RequestQueue;

import java.util.List;

import static org.swisspush.gateleen.core.util.HttpRequestHeader.CONTENT_LENGTH;
import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * Manager class for the reduced propagation feature. The manager starts timers based on incoming requests. The timers
 * periodically check whether there are expired queues to process.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ReducedPropagationManager {

    private Vertx vertx;
    private final ReducedPropagationStorage storage;
    private final RequestQueue requestQueue;
    public static final String LOCK_REQUESTER = "ReducedPropagationManager";
    public static final String MANAGER_QUEUE_PREFIX = "manager_";

    public static final String QUEUE = "queue";
    public static final String MANAGER_QUEUE = "manager_queue";

    public static final String PROCESSOR_ADDRESS = "gateleen.hook-expired-queues-processor";

    private long processExpiredQueuesTimerId = -1;

    private Logger log = LoggerFactory.getLogger(ReducedPropagationManager.class);

    public ReducedPropagationManager(Vertx vertx, ReducedPropagationStorage storage, RequestQueue requestQueue) {
        this.vertx = vertx;
        this.storage = storage;
        this.requestQueue = requestQueue;

        registerExpiredQueueProcessor();
    }

    /**
     * Start the periodic check to process expired queues.
     *
     * @param interval interval in milliseconds
     */
    public void startExpiredQueueProcessing(long interval) {
        log.info("About to start periodic processing of expired queues with an interval of " + interval + " ms");
        vertx.cancelTimer(processExpiredQueuesTimerId);
        processExpiredQueuesTimerId = vertx.setPeriodic(interval, event -> processExpiredQueues());
    }

    /**
     * The processing of incoming requests contains the following steps:
     * <ul>
     * <li>Lock the originally defined queue and enqueue the request</li>
     * <li>Add the queue name to the storage with an expiration value based on the propagationInterval parameter</li>
     * <li>When the queue name is already in the storage, a running timer exists. Nothing more to do</li>
     * <li>When the queue name not exists in the storage, a new timer was started. Enqueue the request without payload into an additional locked 'manager' queue</li>
     * </ul>
     *
     * @param method              http method of the queued request
     * @param targetUri           targetUri of the queued request
     * @param queueHeaders        headers of the queued request
     * @param payload             payload of the queued request
     * @param queue               the queue name
     * @param propagationInterval the propagation interval in seconds defining how long to prevent from propagating changes to the resource
     * @param doneHandler         a handler which is called as soon as the request is written into the queue.
     * @return a future when the processing is done
     */
    public Future<Void> processIncomingRequest(HttpMethod method, String targetUri, MultiMap queueHeaders, Buffer payload, String queue, long propagationInterval, Handler<Void> doneHandler) {
        Future<Void> future = Future.future();

        long expireTS = System.currentTimeMillis() + (propagationInterval * 1000);

        log.info("Going to perform a lockedEnqueue for (original) queue '" + queue + "' and eventually starting a new timer");
        requestQueue.lockedEnqueue(new HttpRequest(method, targetUri, queueHeaders, payload.getBytes()), queue, LOCK_REQUESTER, doneHandler);

        storage.addQueue(queue, expireTS).setHandler(event -> {
            if (event.failed()) {
                log.error("starting a new timer for queue '" + queue + "' and propagationInterval '" + propagationInterval + "' failed. Cause: " + event.cause());
                future.fail(event.cause());
                return;
            }
            if (event.result()) {
                log.info("Timer for queue '" + queue + "' with expiration at '" + expireTS + "' started.");
                storeQueueRequest(queue, method, targetUri, queueHeaders).setHandler(storeResult -> {
                    if(storeResult.failed()){
                        future.fail(storeResult.cause());
                    } else {
                        future.complete();
                    }
                });
            } else {
                log.info("Timer for queue '" + queue + "' is already running.");
                future.complete();
            }
        });
        return future;
    }

    private Future<Void> storeQueueRequest(String queue, HttpMethod method, String targetUri, MultiMap queueHeaders){
        log.info("Going to write the queue request for queue '"+queue+"' to the storage");
        Future<Void> future = Future.future();

        MultiMap queueHeadersCopy = new CaseInsensitiveHeaders().addAll(queueHeaders);
        if (HttpRequestHeader.containsHeader(queueHeadersCopy, CONTENT_LENGTH)) {
            queueHeadersCopy.set(CONTENT_LENGTH.getName(), "0");
        }
        HttpRequest request = new HttpRequest(method, targetUri, queueHeadersCopy, null);
        storage.storeQueueRequest(queue, request.toJsonObject()).setHandler(storeResult -> {
            if(storeResult.failed()){
                log.error("Storing the queue request for queue '" + queue + "' failed. Cause: " + storeResult.cause());
                future.fail(storeResult.cause());
            } else {
                log.info("Successfully stored the queue request for queue '"+queue+"'");
                future.complete();
            }
        });
        return future;
    }

    private void processExpiredQueues() {
        log.info("Going to process expired queues");
        storage.removeExpiredQueues(System.currentTimeMillis()).setHandler(event -> {
            if (event.failed()) {
                log.error("Failed to process expired queues. Cause: " + event.cause());
                return;
            }
            List<String> expiredQueues = event.result();
            log.info("Got " + expiredQueues.size() + " expired queues to process");
            for (String expiredQueue : expiredQueues) {
                log.info("About to process expired queue '" + expiredQueue + "'");
                vertx.eventBus().send(PROCESSOR_ADDRESS, new JsonObject().put(QUEUE, expiredQueue).put(MANAGER_QUEUE, MANAGER_QUEUE_PREFIX + expiredQueue),
                        (Handler<AsyncResult<Message<JsonObject>>>) event1 -> {
                            if (!OK.equals(event1.result().body().getString(STATUS))) {
                                log.error("Failed to process expired queue. Message: " + event1.result().body().getString(MESSAGE));
                            } else {
                                log.info(event1.result().body().getString(MESSAGE));
                            }
                        });
            }
        });
    }

    /**
     * Registers a consumer for expired queue processing. When a queue has expired, the corresponding manager queue
     * has to be unlocked and the original queue has to be deleted and also unlocked.
     */
    private void registerExpiredQueueProcessor() {
        vertx.eventBus().consumer(PROCESSOR_ADDRESS, (Handler<Message<JsonObject>>) event -> {
            String queue = event.body().getString(QUEUE);
            String manager_queue = event.body().getString(MANAGER_QUEUE);
            requestQueue.deleteLock(manager_queue).setHandler(deleteLockResult -> {
                if(deleteLockResult.succeeded()) {
                    requestQueue.deleteAllQueueItems(queue, true).setHandler(deleteAllQueueItemsResult -> {
                        if(deleteAllQueueItemsResult.succeeded()){
                            event.reply(new JsonObject().put(STATUS, OK).put(MESSAGE, "Successfully unlocked manager queue " + manager_queue + " and deleted all queue items of queue " + queue));
                        } else {
                            event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, deleteAllQueueItemsResult.cause().getMessage()));
                        }
                    });
                } else {
                    event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, deleteLockResult.cause().getMessage()));
                }
            });
        });
    }
}
