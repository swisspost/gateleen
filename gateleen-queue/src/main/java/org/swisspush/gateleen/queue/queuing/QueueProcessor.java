package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.queue.expiry.ExpiryCheckHandler;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreaker;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitState;

import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class QueueProcessor {

    private Vertx vertx;
    private HttpClient httpClient;
    private MonitoringHandler monitoringHandler;

    public QueueProcessor(final Vertx vertx, final HttpClient httpClient, final MonitoringHandler monitoringHandler) {
        this(vertx, httpClient, monitoringHandler, null);
    }

    public QueueProcessor(final Vertx vertx, final HttpClient httpClient, final MonitoringHandler monitoringHandler, QueueCircuitBreaker queueCircuitBreaker) {
        this.vertx = vertx;
        this.httpClient = httpClient;
        this.monitoringHandler = monitoringHandler;

        vertx.eventBus().localConsumer(Address.queueProcessorAddress(), new Handler<Message<JsonObject>>() {
            public void handle(final Message<JsonObject> message) {
                HttpRequest queuedRequestTry = null;
                try {
                    queuedRequestTry = new HttpRequest(new JsonObject(message.body().getString("payload")));
                } catch (Exception exception) {
                    LoggerFactory.getLogger(QueueProcessor.class).error("QUEUE_ERROR: Could not build request: " + message.body().toString() + " error is " + exception.getMessage());
                    message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, exception.getMessage()));
                    return;
                }
                final HttpRequest queuedRequest = queuedRequestTry;
                final Logger logger = RequestLoggerFactory.getLogger(QueueProcessor.class, queuedRequest.getHeaders());
                if (logger.isTraceEnabled()) {
                    logger.trace("process message: " + message);
                }

                String queueName = message.body().getString("queue");

                if(queueCircuitBreaker == null || !queueCircuitBreaker.isActive()){
                    executeQueuedRequest(message, logger, queuedRequest);
                } else {
                    queueCircuitBreaker.handleQueuedRequest(queueName, queuedRequest).setHandler(event -> {
                        if(event.failed()){
                            String msg = "Error in QueueCircuitBreaker occurred for queue " + queueName + ". Reply with status ERROR. Message is: " + event.cause().getMessage();
                            logger.error(msg);
                            message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, msg));
                            return;
                        }

                        if(QueueCircuitState.OPEN == event.result()) {
                            message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, "Circuit for queue " + queueName + " is " + event.result() + ". Queues using this endpoint are not allowed to be executed right now"));
                        } else {
                            executeQueuedRequest(message, logger, queuedRequest);
                        }
                    });
                }
            }
        });
    }

    private void executeQueuedRequest(Message<JsonObject> message, Logger logger, HttpRequest queuedRequest){
        vertx.eventBus().send(Address.redisquesAddress(), buildGetLockOperation(message.body().getString("queue")), new Handler<AsyncResult<Message<JsonObject>>>(){
            @Override
            public void handle(AsyncResult<Message<JsonObject>> reply) {
                if (NO_SUCH_LOCK.equals(reply.result().body().getString(STATUS))) {
                    logger.debug("performing request " + queuedRequest.getMethod() + " " + queuedRequest.getUri());

                    if (ExpiryCheckHandler.isExpired(queuedRequest)) {
                        logger.debug("request expired to " + queuedRequest.getUri());
                        message.reply(new JsonObject().put(STATUS, OK));
                        return;
                    }

                    HttpClientRequest request = httpClient.request(queuedRequest.getMethod(), queuedRequest.getUri(), response -> {
                        if (logger.isTraceEnabled()) {
                            logger.trace("response: " + response.statusCode());
                        }
                        if (response.statusCode() >= 200 && response.statusCode() < 300 || response.statusCode() == 409) {
                            if (response.statusCode() != StatusCode.CONFLICT.getStatusCode()) {
                                logger.debug("Successful request to " + queuedRequest.getUri());
                            } else {
                                logger.warn("Ignoring request conflict to " + queuedRequest.getUri() + ": " + response.statusCode() + " " + response.statusMessage());
                            }
                            message.reply(new JsonObject().put(STATUS, OK));
                            monitoringHandler.updateDequeue();
                        } else {
                            logger.error("QUEUE_ERROR: Failed request to " + queuedRequest.getUri() + ": " + response.statusCode() + " " + response.statusMessage());
                            message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, response.statusCode() + " " + response.statusMessage()));
                        }
                        response.bodyHandler(event -> logger.debug("Discarding backend body"));
                        response.endHandler(event -> logger.debug("Backend response end"));
                        response.exceptionHandler(exception -> {
                            logger.warn("QUEUE_ERROR: Exception on response from " + queuedRequest.getUri() + ": " + exception.getMessage());
                            message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, exception.getMessage()));
                        });
                    });

                    if (queuedRequest.getHeaders() != null && !queuedRequest.getHeaders().isEmpty()) {
                        request.headers().setAll(queuedRequest.getHeaders());
                    }

                    request.exceptionHandler(exception -> {
                        logger.warn("QUEUE_ERROR: Failed request to " + queuedRequest.getUri() + ": " + exception.getMessage());
                        message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, exception.getMessage()));
                    });
                    request.setTimeout(120000); // avoids blocking other requests
                    if (queuedRequest.getPayload() != null) {
                        request.end(Buffer.buffer(queuedRequest.getPayload()));
                    } else {
                        request.end();
                    }
                } else {
                    logger.warn("Queue {} is locked!", message.body().getString("queue"));
                    message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, "queue locked"));
                }
            }
        });

    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}
