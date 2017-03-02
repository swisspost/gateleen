package org.swisspush.gateleen.hook.reducedpropagation;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.HttpRequestHeader;
import org.swisspush.gateleen.queue.queuing.RequestQueue;

import java.util.List;

import static org.swisspush.gateleen.core.util.HttpRequestHeader.CONTENT_LENGTH;

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

    private long processExpiredQueuesTimerId = -1;

    private Logger log = LoggerFactory.getLogger(ReducedPropagationManager.class);

    public ReducedPropagationManager(Vertx vertx, ReducedPropagationStorage storage, RequestQueue requestQueue, long removeExpiredQueuesInterval) {
        this.vertx = vertx;
        this.storage = storage;
        this.requestQueue = requestQueue;
    }

    /**
     * Start the periodic check to process expired queues.
     *
     * @param interval interval in milliseconds
     */
    public void startExpiredQueueProcessing(long interval){
        log.info("About to start periodic processing of expired queues with an interval of " + interval + " ms");
        vertx.cancelTimer(processExpiredQueuesTimerId);
        processExpiredQueuesTimerId = vertx.setPeriodic(interval, event -> processExpiredQueues());
    }

    /**
     * The processing of incoming requests contains the following steps:
     * <ul>
     *     <li>Lock the originally defined queue and enqueue the request</li>
     *     <li>Add the queue name to the storage with an expiration value based on the propagationInterval parameter</li>
     *     <li>When the queue name is already in the storage, a running timer exists. Nothing more to do</li>
     *     <li>When the queue name not exists in the storage, a new timer was started. Enqueue the request without payload into an additional locked 'manager' queue</li>
     * </ul>
     *
     * @param method http method of the queued request
     * @param targetUri targetUri of the queued request
     * @param queueHeaders headers of the queued request
     * @param payload payload of the queued request
     * @param queue the queue name
     * @param propagationInterval the propagation interval in seconds defining how long to prevent from propagating changes to the resource
     * @param doneHandler a handler which is called as soon as the request is written into the queue.
     * @return a future when the processing is done
     */
    public Future<Void> processIncomingRequest(HttpMethod method, String targetUri, MultiMap queueHeaders, Buffer payload, String queue, long propagationInterval, Handler<Void> doneHandler) {
        Future<Void> future = Future.future();

        long expireTS = System.currentTimeMillis() + (propagationInterval * 1000);

        log.info("Going to perform a lockedEnqueue for (original) queue '"+queue+"' and eventually starting a new timer");
        requestQueue.lockedEnqueue(new HttpRequest(method, targetUri, queueHeaders, payload.getBytes()), queue, LOCK_REQUESTER, doneHandler);

        storage.addQueue(queue, expireTS).setHandler(event -> {
            if (event.failed()) {
                log.error("starting a new timer for queue '" + queue + "' and propagationInterval '" + propagationInterval + "' failed. Cause: " + event.cause());
                future.fail(event.cause());
                return;
            }
            if (event.result()) {
                log.info("Timer for queue '" + queue + "' with expiration at '" + expireTS + "' started.");
                enqueueManagerQueue(queue, method, targetUri, queueHeaders, propagationInterval);
            } else {
                log.info("Timer for queue '" + queue + "' is already running.");
            }
            future.complete();
        });

        return future;
    }

    private void enqueueManagerQueue(String queue, HttpMethod method, String targetUri, MultiMap queueHeaders, long propagationInterval){
        MultiMap queueHeadersCopy = new CaseInsensitiveHeaders().addAll(queueHeaders);
        String managerQueue = MANAGER_QUEUE_PREFIX + queue;
        log.info("Going to perform a lockedEnqueue with a manager queue called '"+managerQueue+"' for (original) queue '"+queue+"'");
        if(HttpRequestHeader.containsHeader(queueHeadersCopy, CONTENT_LENGTH)) {
            queueHeadersCopy.set(CONTENT_LENGTH.getName(), "0");
        }
        requestQueue.lockedEnqueue(new HttpRequest(method, targetUri, queueHeadersCopy, null), managerQueue, LOCK_REQUESTER, null);
    }

    private void processExpiredQueues(){
        log.info("Going to process expired queues");
        storage.removeExpiredQueues(System.currentTimeMillis()).setHandler(event -> {
            if(event.failed()){
                log.error("Failed to process expired queues. Cause: " + event.cause());
                return;
            }
            List<String> expiredQueues = event.result();
            for (String expiredQueue : expiredQueues) {
                log.info("About to process expired Queue '"+expiredQueue+"'");
            }
        });
    }
}
