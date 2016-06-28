package org.swisspush.gateleen.queue.queuing;

import org.swisspush.gateleen.core.util.Address;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.queue.expiry.ExpiryCheckHandler;

import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * The QueueClient allows you to enqueue various requests.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class QueueClient implements RequestQueue {
    private MonitoringHandler monitoringHandler;
    private Vertx vertx;

    /**
     * Creates a new instance of the QueueClient.
     * 
     * @param vertx vertx
     * @param monitoringHandler monitoringHandler
     */
    public QueueClient(Vertx vertx, MonitoringHandler monitoringHandler) {
        this.vertx = vertx;
        this.monitoringHandler = monitoringHandler;
    }

    /**
     * Enqueues the given request.
     * 
     * @param request request
     * @param buffer buffer
     * @param queue queue
     */
    @Override
    public void enqueue(final HttpServerRequest request, Buffer buffer, final String queue) {
        enqueue(request, request.headers(), buffer, queue);
    }

    /**
     * Enqueues the given request.
     * 
     * @param request request
     * @param headers headers
     * @param buffer buffer
     * @param queue queue
     */
    @Override
    public void enqueue(final HttpServerRequest request, MultiMap headers, Buffer buffer, final String queue) {
        HttpRequest queuedRequest = new HttpRequest(request.method(), request.uri(), headers, buffer.getBytes());
        enqueue(request, queuedRequest, queue);
    }

    /**
     * Enqueues a disconnected request.
     * 
     * @param request - selfmade request
     * @param queue queue
     */
    @Override
    public void enqueue(HttpRequest request, final String queue) {
        enqueue(null, request, queue);
    }

    /**
     * Enqueues a disconnected request.
     * 
     * @param request - selfmade request
     * @param queue queue
     * @param doneHandler a handler which is called as soon as the request is written into the queue.
     */
    @Override
    public void enqueue(HttpRequest request, final String queue, final Handler<Void> doneHandler) {
        enqueue(null, request, queue, doneHandler);
    }

    /**
     * Enques a request. <br />
     * If no X-Server-Timestamp and / or X-Expire-After headers
     * are set, the server sets the actual timestamp and a default
     * expiry time of 2 seconds.
     * 
     * @param request - a client made request, therefor connected, can take responses. The request can be null, if only a selfmade request is available.
     * @param queuedRequest - a selfmade request, not connected to a client, can't take responses!
     * @param queue queue
     */
    private void enqueue(final HttpServerRequest request, HttpRequest queuedRequest, final String queue) {
        enqueue(request, queuedRequest, queue, null);
    }

    /**
     * Enques a request. <br />
     * If no X-Server-Timestamp and / or X-Expire-After headers
     * are set, the server sets the actual timestamp and a default
     * expiry time of 2 seconds.
     * 
     * @param request - a client made request, therefor connected, can take responses. The request can be null, if only a selfmade request is available.
     * @param queuedRequest - a selfmade request, not connected to a client, can't take responses!
     * @param queue queue
     * @param doneHandler a handler which is called as soon as the request is written into the queue.
     */
    private void enqueue(final HttpServerRequest request, HttpRequest queuedRequest, final String queue, final Handler<Void> doneHandler) {
        // setting a server-timestamp header if not already set
        ExpiryCheckHandler.updateServerTimestampHeader(queuedRequest);
        vertx.eventBus().send(Address.redisquesAddress(), buildEnqueueOperation(queue, queuedRequest.toJsonObject().encode()), new Handler<AsyncResult<Message<JsonObject>>>() {
            @Override
            public void handle(AsyncResult<Message<JsonObject>> event) {
                if (OK.equals(event.result().body().getString(STATUS))) {
                    monitoringHandler.updateLastUsedQueueSizeInformation(queue);
                    monitoringHandler.updateEnqueue();

                    if (request != null) {
                        request.response().setStatusCode(StatusCode.ACCEPTED.getStatusCode());
                        request.response().setStatusMessage(StatusCode.ACCEPTED.getStatusMessage());
                        request.response().end();
                    }
                } else if (request != null) {
                    request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                    request.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
                    request.response().end(event.result().body().getString(MESSAGE));
                }

                // call the done handler to tell,
                // that the request was written
                if (doneHandler != null) {
                    doneHandler.handle(null);
                }
            }
        });
    }
}
