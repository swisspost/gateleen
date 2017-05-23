package org.swisspush.gateleen.queue.queuing;

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
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType;

import static org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType.FAILURE;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType.SUCCESS;
import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class QueueProcessor {

    private Vertx vertx;
    private HttpClient httpClient;
    private MonitoringHandler monitoringHandler;
    private QueueCircuitBreaker queueCircuitBreaker;

    public QueueProcessor(final Vertx vertx, final HttpClient httpClient, final MonitoringHandler monitoringHandler) {
        this(vertx, httpClient, monitoringHandler, null);
    }

    public QueueProcessor(final Vertx vertx, final HttpClient httpClient, final MonitoringHandler monitoringHandler, QueueCircuitBreaker queueCircuitBreaker) {
        this.vertx = vertx;
        this.httpClient = httpClient;
        this.monitoringHandler = monitoringHandler;
        this.queueCircuitBreaker = queueCircuitBreaker;

        vertx.eventBus().localConsumer(getQueueProcessorAddress(), (Handler<Message<JsonObject>>) message -> {
            HttpRequest queuedRequestTry = null;
            JsonObject jsonRequest = new JsonObject(message.body().getString("payload"));
            try {
                queuedRequestTry = new HttpRequest(jsonRequest);
            } catch (Exception exception) {
                LoggerFactory.getLogger(QueueProcessor.class).error("Could not build request: " + message.body().toString() + " error is " + exception.getMessage());
                message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, exception.getMessage()));
                return;
            }
            final HttpRequest queuedRequest = queuedRequestTry;
            final Logger logger = RequestLoggerFactory.getLogger(QueueProcessor.class, queuedRequest.getHeaders());
            if (logger.isTraceEnabled()) {
                logger.trace("process message: " + message);
            }

            String queueName = message.body().getString("queue");

            if (!isCircuitCheckEnabled()) {
                executeQueuedRequest(message, logger, queuedRequest, jsonRequest, queueName, null);
            } else {
                queueCircuitBreaker.handleQueuedRequest(queueName, queuedRequest).setHandler(event -> {
                    if (event.failed()) {
                        String msg = "Error in QueueCircuitBreaker occurred for queue " + queueName + ". Reply with status ERROR. Message is: " + event.cause().getMessage();
                        logger.error(msg);
                        message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, msg));
                        return;
                    }
                    QueueCircuitState state = event.result();
                    if (QueueCircuitState.OPEN == state) {
                        message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, "Circuit for queue " + queueName + " is " + state + ". Queues using this endpoint are not allowed to be executed right now"));
                    } else {
                        executeQueuedRequest(message, logger, queuedRequest, jsonRequest, queueName, state);
                    }
                });
            }
        });
    }

    public String getQueueProcessorAddress(){
        return Address.queueProcessorAddress();
    }

    private boolean isCircuitCheckEnabled() {
        return queueCircuitBreaker != null && queueCircuitBreaker.isCircuitCheckEnabled();
    }

    private boolean isStatisticsUpdateEnabled() {
        return queueCircuitBreaker != null && queueCircuitBreaker.isStatisticsUpdateEnabled();
    }

    private void performCircuitBreakerActions(String queueName, HttpRequest queuedRequest, QueueResponseType queueResponseType, QueueCircuitState state) {
        updateCircuitBreakerStatistics(queueName, queuedRequest, queueResponseType, state);
        if (QueueCircuitState.HALF_OPEN == state) {
            if (SUCCESS == queueResponseType) {
                closeCircuit(queuedRequest);
            } else if (FAILURE == queueResponseType) {
                reOpenCircuit(queuedRequest);
            }
        }
    }

    private void updateCircuitBreakerStatistics(String queueName, HttpRequest queuedRequest, QueueResponseType queueResponseType, QueueCircuitState state) {
        if (isStatisticsUpdateEnabled() && QueueCircuitState.OPEN != state) {
            queueCircuitBreaker.updateStatistics(queueName, queuedRequest, queueResponseType).setHandler(event -> {
                if (event.failed()) {
                    String message = "failed to update statistics for queue '" + queueName + "' to uri " + queuedRequest.getUri() +
                            ". Message is: " + event.cause().getMessage();
                    RequestLoggerFactory.getLogger(QueueProcessor.class, queuedRequest.getHeaders()).warn(message);
                }
            });
        }
    }

    private void closeCircuit(HttpRequest queuedRequest) {
        if (queueCircuitBreaker != null) {
            queueCircuitBreaker.closeCircuit(queuedRequest).setHandler(event -> {
                if (event.failed()) {
                    String message = "failed to close circuit " + queuedRequest.getUri() +
                            ". Message is: " + event.cause().getMessage();
                    RequestLoggerFactory.getLogger(QueueProcessor.class, queuedRequest.getHeaders()).error(message);
                }
            });
        }
    }

    private void reOpenCircuit(HttpRequest queuedRequest) {
        if (queueCircuitBreaker != null) {
            queueCircuitBreaker.reOpenCircuit(queuedRequest).setHandler(event -> {
                if (event.failed()) {
                    String message = "failed to re-open circuit " + queuedRequest.getUri() +
                            ". Message is: " + event.cause().getMessage();
                    RequestLoggerFactory.getLogger(QueueProcessor.class, queuedRequest.getHeaders()).error(message);
                }
            });
        }
    }

    private void executeQueuedRequest(Message<JsonObject> message, Logger logger, HttpRequest queuedRequest,
                                      JsonObject jsonRequest, String queueName, QueueCircuitState state) {

        logger.debug("performing request " + queuedRequest.getMethod() + " " + queuedRequest.getUri());
        if (ExpiryCheckHandler.isExpired(queuedRequest.getHeaders(), jsonRequest.getLong(QueueClient.QUEUE_TIMESTAMP))) {
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
                performCircuitBreakerActions(queueName, queuedRequest, SUCCESS, state);
                monitoringHandler.updateDequeue();
            } else {
                logger.info("Failed queued request to " + queuedRequest.getUri() + ": " + response.statusCode() + " " + response.statusMessage());
                message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, response.statusCode() + " " + response.statusMessage()));
                performCircuitBreakerActions(queueName, queuedRequest, FAILURE, state);
            }
            response.bodyHandler(event -> logger.debug("Discarding backend body"));
            response.endHandler(event -> logger.debug("Backend response end"));
            response.exceptionHandler(exception -> {
                logger.warn("Exception on response from " + queuedRequest.getUri() + ": " + exception.getMessage());
                message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, exception.getMessage()));
                performCircuitBreakerActions(queueName, queuedRequest, FAILURE, state);
            });
        });

        if (queuedRequest.getHeaders() != null && !queuedRequest.getHeaders().isEmpty()) {
            request.headers().setAll(queuedRequest.getHeaders());
        }

        request.exceptionHandler(exception -> {
            logger.warn("Failed request to " + queuedRequest.getUri() + ": " + exception.getMessage());
            message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, exception.getMessage()));
            performCircuitBreakerActions(queueName, queuedRequest, FAILURE, state);
        });
        request.setTimeout(120000); // avoids blocking other requests
        if (queuedRequest.getPayload() != null) {
            request.end(Buffer.buffer(queuedRequest.getPayload()));
        } else {
            request.end();
        }
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}
