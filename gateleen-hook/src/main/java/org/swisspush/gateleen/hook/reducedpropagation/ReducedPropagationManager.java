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
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.HttpRequestHeader;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.queue.queuing.RequestQueue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.swisspush.gateleen.core.util.HttpRequestHeader.CONTENT_LENGTH;
import static org.swisspush.gateleen.core.util.LockUtil.acquireLock;
import static org.swisspush.gateleen.core.util.LockUtil.releaseLock;
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
    private Lock lock;
    public static final String PROCESS_EXPIRED_QUEUES_LOCK = "reducedPropagationProcExpQueuesLock";
    public static final String LOCK_REQUESTER = "ReducedPropagationManager";
    public static final String PROCESSOR_ADDRESS = "gateleen.hook-expired-queues-processor";
    public static final String MANAGER_QUEUE_PREFIX = "manager_";

    private static final int MAX_QUEUE_RETRY_COUNT = 50;

    private long processExpiredQueuesTimerId = -1;

    private Map<String, Integer> failedQueueRetries = new HashMap<>();
    private Random random = new Random();

    private Logger log = LoggerFactory.getLogger(ReducedPropagationManager.class);

    public ReducedPropagationManager(Vertx vertx, ReducedPropagationStorage storage, RequestQueue requestQueue, Lock lock) {
        this.vertx = vertx;
        this.storage = storage;
        this.requestQueue = requestQueue;
        this.lock = lock;

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
        processExpiredQueuesTimerId = vertx.setPeriodic(intervalMs, event -> {
            final String token = createToken("reducedpropagation_expired_queue_processing");
            acquireLock(this.lock, PROCESS_EXPIRED_QUEUES_LOCK, token, getLockExpiry(intervalMs), log).setHandler(lockEvent -> {
                if(lockEvent.succeeded()){
                    if(lockEvent.result()){
                        processExpiredQueues(token);
                    }
                } else {
                    log.error("Could not acquire lock '"+PROCESS_EXPIRED_QUEUES_LOCK+"'. Message: " + lockEvent.cause().getMessage());
                }
            });
        });
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

    private void processExpiredQueues(String lockToken) {
        log.debug("Going to process expired queues");
        storage.removeExpiredQueues(System.currentTimeMillis()).setHandler(event -> {
            if (event.failed()) {
                log.error("Going to release lock because process expired queues failed. Cause: " + event.cause());
                releaseLock(this.lock, PROCESS_EXPIRED_QUEUES_LOCK, lockToken, log);
                return;
            }
            List<String> expiredQueues = event.result();
            log.debug("Got " + expiredQueues.size() + " expired queues to process");
            for (String expiredQueue : expiredQueues) {
                log.info("About to notify a consumer to process expired queue '" + expiredQueue + "'");
                vertx.eventBus().send(PROCESSOR_ADDRESS, expiredQueue, (Handler<AsyncResult<Message<JsonObject>>>) event1 -> {
                    if(event1.failed()){
                        log.error("Failed to process expired queue '"+expiredQueue+"'. Cause: " + event1.cause());
                        handleFailedQueueRetry(expiredQueue);
                    } else {
                        failedQueueRetries.remove(expiredQueue);
                        if (!OK.equals(event1.result().body().getString(STATUS))) {
                            log.error("Failed to process expired queue '"+expiredQueue+"'. Message: " + event1.result().body().getString(MESSAGE));
                        } else {
                            log.info(event1.result().body().getString(MESSAGE));
                        }
                    }
                });
            }
        });
    }

    private void handleFailedQueueRetry(String expiredQueue){
        int failedQueueRetryCount = getFailedQueueRetryCount(expiredQueue);
        if(failedQueueRetryCount < MAX_QUEUE_RETRY_COUNT){
            int updatedRetryCount = failedQueueRetryCount + 1;
            failedQueueRetries.put(expiredQueue, updatedRetryCount);
            int randomExpiry = random.nextInt(30000 - 2000) + 2000; // expiry between 2sec and 30sec
            log.info("Retry attempt #" + updatedRetryCount + " to process expired queue '"+expiredQueue+"' again in " + randomExpiry + "ms");
            storage.addQueue(expiredQueue, System.currentTimeMillis() + randomExpiry).setHandler(addQueueReply -> {
                if(addQueueReply.failed()){
                    log.error("attempt #" + updatedRetryCount + " failed to add queue '"+expiredQueue+"' again for a later retry. Cause: " + addQueueReply.cause());
                }
            });
        } else {
            log.warn("Too many retries for expired queue '"+expiredQueue+"'. Not going to retry again");
            failedQueueRetries.remove(expiredQueue);
        }
    }

    private int getFailedQueueRetryCount(String expiredQueue){
        Integer retryCount = failedQueueRetries.get(expiredQueue);
        if(retryCount == null){
            return 0;
        }
        return retryCount;
    }

    private long getLockExpiry(long interval){
        if(interval <= 1){
            return 1;
        }
        return interval / 2;
    }

    private String createToken(String appendix){
        return Address.instanceAddress()+ "_" + System.currentTimeMillis() + "_" + appendix;
    }

    private void registerExpiredQueueProcessor() {
        vertx.eventBus().consumer(PROCESSOR_ADDRESS, (Handler<Message<String>>) event -> processExpiredQueue(event.body(), event));
    }

    private void processExpiredQueue(String queue, Message<String> event) {
        log.info("about to process expired queue '"+queue+"'");
        if(StringUtils.isEmpty(queue)){
            event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, "Tried to process an expired queue without a valid queue name. Going to stop here"));
            return;
        }

        // 1. get queue request from storage
        log.debug("get queue request for queue '"+queue+"'");
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
                log.debug("going to delete all queue items of manager queue '"+managerQueue+"'");
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
                        log.debug("going to enqueue into manager queue '"+managerQueue+"'");
                        requestQueue.enqueueFuture(request, managerQueue).setHandler(enqueueResult -> {
                            if(enqueueResult.failed()){
                                event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, enqueueResult.cause().getMessage()));
                            } else {
                                // 3. remove queue request from storage
                                log.debug("going to remove queue request from storage of queue '"+queue+"'");
                                storage.removeQueueRequest(queue).setHandler(removeQueueRequestResult -> {
                                    if(removeQueueRequestResult.failed()){
                                        log.error("Failed to remove request for queue '"+queue+"'. Remove it manually to remove expired data from storage");
                                    }

                                    // 4. deleteAllQueueItems + deleteLock of original queue
                                    log.debug("going to unlock and delete all queue items of queue '"+queue+"'");
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
