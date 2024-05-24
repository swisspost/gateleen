package org.swisspush.gateleen.hook.reducedpropagation;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.HttpRequestHeader;
import org.swisspush.gateleen.core.util.LockUtil;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.queue.queuing.RequestQueue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.swisspush.gateleen.core.util.HttpRequestHeader.CONTENT_LENGTH;
import static org.swisspush.gateleen.core.util.LockUtil.acquireLock;
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
    private final LockUtil lockUtil;
    public static final String PROCESS_EXPIRED_QUEUES_LOCK = "reducedPropagationProcExpQueuesLock";
    public static final String LOCK_REQUESTER = "ReducedPropagationManager";
    public static final String PROCESSOR_ADDRESS = "gateleen.hook-expired-queues-processor";
    public static final String MANAGER_QUEUE_PREFIX = "manager_";

    private static final int MAX_QUEUE_RETRY_COUNT = 50;

    private long processExpiredQueuesTimerId = -1;

    private Map<String, Integer> failedQueueRetries = new HashMap<>();
    private Random random = new Random();

    private Logger log = LoggerFactory.getLogger(ReducedPropagationManager.class);

    public ReducedPropagationManager(
        Vertx vertx,
        ReducedPropagationStorage storage,
        RequestQueue requestQueue,
        Lock lock,
        GateleenExceptionFactory exceptionFactory
    ) {
        this.vertx = vertx;
        this.storage = storage;
        this.requestQueue = requestQueue;
        this.lock = lock;
        this.lockUtil = new LockUtil(exceptionFactory);

        registerExpiredQueueProcessor();
    }

    /**
     * Start the periodic check to process expired queues.
     *
     * @param intervalMs interval in milliseconds
     */
    public void startExpiredQueueProcessing(long intervalMs) {
        log.info("About to start periodic processing of expired queues with an interval of {} ms", intervalMs);
        vertx.cancelTimer(processExpiredQueuesTimerId);
        processExpiredQueuesTimerId = vertx.setPeriodic(intervalMs, event -> {
            final String token = createToken("reducedpropagation_expired_queue_processing");
            acquireLock(this.lock, PROCESS_EXPIRED_QUEUES_LOCK, token, getLockExpiry(intervalMs), log).onComplete(lockEvent -> {
                if(lockEvent.succeeded()){
                    if(lockEvent.result()){
                        processExpiredQueues(token);
                    }
                } else {
                    log.error("Could not acquire lock '{}'. Message: {}", PROCESS_EXPIRED_QUEUES_LOCK, lockEvent.cause().getMessage());
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
    public Promise<Void> processIncomingRequest(HttpMethod method, String targetUri, MultiMap queueHeaders, Buffer payload, String queue, long propagationIntervalMs, Handler<Void> doneHandler) {
        Promise<Void> promise = Promise.promise();

        long expireTS = System.currentTimeMillis() + propagationIntervalMs;

        log.debug("Going to perform a lockedEnqueue for (original) queue '{}' and eventually starting a new timer", queue);
        requestQueue.lockedEnqueue(new HttpRequest(method, targetUri, queueHeaders, payload.getBytes()), queue, LOCK_REQUESTER, doneHandler);

        storage.addQueue(queue, expireTS).onComplete(event -> {
            if (event.failed()) {
                log.error("starting a new timer for queue '{}' and propagationIntervalMs '{}' failed. Cause: {}",
                        queue, propagationIntervalMs, event.cause());
                promise.fail(event.cause());
                return;
            }
            if (event.result()) {
                log.debug("Timer for queue '{}' with expiration at '{}' started.", queue, expireTS);
                storeQueueRequest(queue, method, targetUri, queueHeaders).future().onComplete(storeResult -> {
                    if (storeResult.failed()) {
                        promise.fail(storeResult.cause());
                    } else {
                        promise.complete();
                    }
                });
            } else {
                log.debug("Timer for queue '{}' is already running.", queue);
                promise.complete();
            }
        });
        return promise;
    }

    private Promise<Void> storeQueueRequest(String queue, HttpMethod method, String targetUri, MultiMap queueHeaders) {
        log.debug("Going to write the queue request for queue '{}' to the storage", queue);
        Promise<Void> promise = Promise.promise();

        MultiMap queueHeadersCopy = MultiMap.caseInsensitiveMultiMap().addAll(queueHeaders);
        if (HttpRequestHeader.containsHeader(queueHeadersCopy, CONTENT_LENGTH)) {
            queueHeadersCopy.set(CONTENT_LENGTH.getName(), "0");
        }
        HttpRequest request = new HttpRequest(method, targetUri, queueHeadersCopy, null);
        storage.storeQueueRequest(queue, request.toJsonObject()).onComplete(storeResult -> {
            if (storeResult.failed()) {
                log.error("Storing the queue request for queue '{}' failed. Cause: {}", queue, storeResult.cause());
                promise.fail(storeResult.cause());
            } else {
                log.debug("Successfully stored the queue request for queue '{}'", queue);
                promise.complete();
            }
        });
        return promise;
    }

    private void processExpiredQueues(String lockToken) {
        log.debug("Going to process expired queues");
        storage.removeExpiredQueues(System.currentTimeMillis()).onComplete(event -> {
            if (event.failed()) {
                log.error("Going to release lock because process expired queues failed. Cause: " + event.cause());
                lockUtil.releaseLock(this.lock, PROCESS_EXPIRED_QUEUES_LOCK, lockToken, log);
                return;
            }
            Response response = event.result();
            log.debug("Got {} expired queues to process", response.size());
            for (Response expiredQueue : response) {
                log.debug("About to notify a consumer to process expired queue '{}'", expiredQueue);
                vertx.eventBus().request(PROCESSOR_ADDRESS, expiredQueue.toString(), (Handler<AsyncResult<Message<JsonObject>>>) event1 -> {
                    if(event1.failed()){
                        log.error("Failed to process expired queue '{}'. Cause: {}", expiredQueue, event1.cause());
                        handleFailedQueueRetry(expiredQueue.toString());
                    } else {
                        failedQueueRetries.remove(expiredQueue.toString());
                        if (!OK.equals(event1.result().body().getString(STATUS))) {
                            log.error("Failed to process expired queue '{}'. Message: {}", expiredQueue,  event1.result().body().getString(MESSAGE));
                        } else {
                            if(log.isDebugEnabled()) {
                                log.debug(event1.result().body().getString(MESSAGE));
                            }
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
            log.info("Retry attempt #{} to process expired queue '{}' again in {}ms", updatedRetryCount, expiredQueue, randomExpiry);
            storage.addQueue(expiredQueue, System.currentTimeMillis() + randomExpiry).onComplete(addQueueReply -> {
                if(addQueueReply.failed()){
                    log.error("attempt #{} failed to add queue '{}' again for a later retry. Cause: {}",
                            updatedRetryCount, expiredQueue, addQueueReply.cause());
                }
            });
        } else {
            log.warn("Too many retries for expired queue '{}'. Not going to retry again", expiredQueue);
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
        log.debug("about to process expired queue '{}'", queue);
        if(StringUtils.isEmpty(queue)){
            event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, "Tried to process an expired queue without a valid queue name. Going to stop here"));
            return;
        }

        // 1. get queue request from storage
        log.debug("get queue request for queue '{}'", queue);
        storage.getQueueRequest(queue).onComplete(getQueuedRequestResult -> {
            if(getQueuedRequestResult.failed()){
                event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, getQueuedRequestResult.cause().getMessage()));
            } else {
                if(getQueuedRequestResult.result() == null){
                    event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, "stored queue request for queue '"+queue+"' is null"));
                    return;
                }

                // 2a. deleteAllQueueItems of manager queue
                String managerQueue = MANAGER_QUEUE_PREFIX + queue;
                log.debug("going to delete all queue items of manager queue '{}'", managerQueue);
                requestQueue.deleteAllQueueItems(managerQueue, false).onComplete(managerQueueDeleteAllResult -> {
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
                        log.debug("going to enqueue into manager queue '{}'", managerQueue);
                        requestQueue.enqueueFuture(request, managerQueue).onComplete(enqueueResult -> {
                            if(enqueueResult.failed()){
                                event.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, enqueueResult.cause().getMessage()));
                            } else {
                                // 3. remove queue request from storage
                                log.debug("going to remove queue request from storage of queue '{}'", queue);
                                storage.removeQueueRequest(queue).onComplete(removeQueueRequestResult -> {
                                    if(removeQueueRequestResult.failed()){
                                        log.error("Failed to remove request for queue '{}'. Remove it manually to " +
                                                "remove expired data from storage", queue);
                                    }

                                    // 4. deleteAllQueueItems + deleteLock of original queue
                                    log.debug("going to unlock and delete all queue items of queue '{}'", queue);
                                    requestQueue.deleteAllQueueItems(queue, true).onComplete(deleteAllQueueItemsResult -> {
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
