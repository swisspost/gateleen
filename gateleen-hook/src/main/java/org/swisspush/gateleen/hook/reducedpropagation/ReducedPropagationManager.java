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
import org.swisspush.gateleen.core.util.StringUtils;
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
    public static final String PROCESSOR_ADDRESS = "gateleen.hook-expired-queues-processor";
    public static final String MANAGER_QUEUE_PREFIX = "manager_";

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
     * @param intervalMs interval in milliseconds
     */
    public void startExpiredQueueProcessing(long intervalMs) {
        log.info("About to start periodic processing of expired queues with an interval of " + intervalMs + " ms");
        vertx.cancelTimer(processExpiredQueuesTimerId);
        processExpiredQueuesTimerId = vertx.setPeriodic(intervalMs, event -> processExpiredQueues());
    }

    /**
     * The processing of incoming requests contains the following steps:
     * <ul>
     * <li>Lock the originally defined queue and enqueue the request</li>
     * <li>Add the queue name to the storage with an expiration value based on the propagationIntervalMs parameter</li>
     * <li>When the queue name is already in the storage, a running timer exists. Nothing more to do</li>
     * <li>When the queue name not exists in the storage, a new timer was started. Enqueue the request without payload into an additional locked 'manager' queue</li>
     * </ul>
     *
     * @param method              http method of the queued request
     * @param targetUri           targetUri of the queued request
     * @param queueHeaders        headers of the queued request
     * @param payload             payload of the queued request
     * @param queue               the queue name
     * @param propagationIntervalMs the propagation interval in milliseconds defining how long to prevent from propagating changes to the resource
     * @param doneHandler         a handler which is called as soon as the request is written into the queue.
     * @return a future when the processing is done
     */
    public Future<Void> processIncomingRequest(HttpMethod method, String targetUri, MultiMap queueHeaders, Buffer payload, String queue, long propagationIntervalMs, Handler<Void> doneHandler) {
        Future<Void> future = Future.future();

        long expireTS = System.currentTimeMillis() + propagationIntervalMs;

        log.info("Going to perform a lockedEnqueue for (original) queue '" + queue + "' and eventually starting a new timer");
        requestQueue.lockedEnqueue(new HttpRequest(method, targetUri, queueHeaders, payload.getBytes()), queue, LOCK_REQUESTER, doneHandler);

        storage.addQueue(queue, expireTS).setHandler(event -> {
            if (event.failed()) {
                log.error("starting a new timer for queue '" + queue + "' and propagationIntervalMs '" + propagationIntervalMs + "' failed. Cause: " + event.cause());
                future.fail(event.cause());
                return;
            }
            if (event.result()) {
                log.info("Timer for queue '" + queue + "' with expiration at '" + expireTS + "' started.");
                storeQueueRequest(queue, method, targetUri, queueHeaders).setHandler(storeResult -> {
                    if (storeResult.failed()) {
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

    private Future<Void> storeQueueRequest(String queue, HttpMethod method, String targetUri, MultiMap queueHeaders) {
        log.info("Going to write the queue request for queue '" + queue + "' to the storage");
        Future<Void> future = Future.future();

        MultiMap queueHeadersCopy = new CaseInsensitiveHeaders().addAll(queueHeaders);
        if (HttpRequestHeader.containsHeader(queueHeadersCopy, CONTENT_LENGTH)) {
            queueHeadersCopy.set(CONTENT_LENGTH.getName(), "0");
        }
        HttpRequest request = new HttpRequest(method, targetUri, queueHeadersCopy, null);
        storage.storeQueueRequest(queue, request.toJsonObject()).setHandler(storeResult -> {
            if (storeResult.failed()) {
                log.error("Storing the queue request for queue '" + queue + "' failed. Cause: " + storeResult.cause());
                future.fail(storeResult.cause());
            } else {
                log.info("Successfully stored the queue request for queue '" + queue + "'");
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
                log.info("About to notify a consumer to process expired queue '" + expiredQueue + "'");
                vertx.eventBus().send(PROCESSOR_ADDRESS, expiredQueue, (Handler<AsyncResult<Message<JsonObject>>>) event1 -> {
                    if (!OK.equals(event1.result().body().getString(STATUS))) {
                        log.error("Failed to process expired queue. Message: " + event1.result().body().getString(MESSAGE));
                    } else {
                        log.info(event1.result().body().getString(MESSAGE));
                    }
                });
            }
        });
    }

    private void registerExpiredQueueProcessor() {
        vertx.eventBus().consumer(PROCESSOR_ADDRESS, (Handler<Message<String>>) event -> {
            processExpiredQueue(event.body(), event);
        });
    }

    private void processExpiredQueue(String queue, Message<String> event) {

        if(StringUtils.isEmpty(queue)){
            event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, "Tried to process an expired queue without a valid queue name. Going to stop here"));
            return;
        }

        // 1. get queue request from storage
        storage.getQueueRequest(queue).setHandler(getQueuedRequestResult -> {
            if(getQueuedRequestResult.failed()){
                event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, getQueuedRequestResult.cause().getMessage()));
            } else {
                if(getQueuedRequestResult.result() == null){
                    event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, "stored queue request for queue '"+queue+"' is null"));
                    return;
                }

                // 2a. deleteAllQueueItems of manager queue
                String managerQueue = MANAGER_QUEUE_PREFIX + queue;
                requestQueue.deleteAllQueueItems(managerQueue, false).setHandler(managerQueueDeleteAllResult -> {
                    if(managerQueueDeleteAllResult.failed()){
                        event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, managerQueueDeleteAllResult.cause().getMessage()));
                    } else {
                        // 2b. enqueue queue request to manager queue
                        HttpRequest request = null;
                        try {
                            request = new HttpRequest(getQueuedRequestResult.result());
                        } catch (Exception ex){
                            event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, ex.getMessage()));
                            return;
                        }
                        requestQueue.enqueueFuture(request, managerQueue).setHandler(enqueueResult -> {
                            if(enqueueResult.failed()){
                                event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, enqueueResult.cause().getMessage()));
                            } else {
                                // 3. remove queue request from storage
                                storage.removeQueueRequest(queue).setHandler(removeQueueRequestResult -> {
                                    if(removeQueueRequestResult.failed()){
                                        log.error("Failed to remove request for queue '"+queue+"'. Remove it manually to remove expired data from storage");
                                    }

                                    // 4. deleteAllQueueItems + deleteLock of original queue
                                    requestQueue.deleteAllQueueItems(queue, true).setHandler(deleteAllQueueItemsResult -> {
                                        if (deleteAllQueueItemsResult.succeeded()) {
                                            event.reply(new JsonObject().put(STATUS, OK).put(MESSAGE, "Successfully deleted lock and all queue items of queue " + queue));
                                        } else {
                                            event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, deleteAllQueueItemsResult.cause().getMessage()));
                                        }
                                    });
                                });
                            }
                        });
                    }
                });
            }
        });

    }
}
