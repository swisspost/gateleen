package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.queue.expiry.ExpiryCheckHandler;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreaker;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueResponseType;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;
import static io.vertx.core.buffer.Buffer.buffer;
import static java.lang.System.currentTimeMillis;
import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenThriftyExceptionFactory;
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
    private final GateleenExceptionFactory exceptionFactory;
    private static final Handler<Buffer> DEV_NULL = buf -> {};
    private MessageConsumer<JsonObject> consumer;

    private Logger log = LoggerFactory.getLogger(QueueProcessor.class);

    public QueueProcessor(final Vertx vertx, final HttpClient httpClient, final MonitoringHandler monitoringHandler) {
        this(vertx, httpClient, monitoringHandler, null);
    }

    public QueueProcessor(final Vertx vertx, final HttpClient httpClient, final MonitoringHandler monitoringHandler, QueueCircuitBreaker queueCircuitBreaker) {
        this(vertx, httpClient, monitoringHandler, queueCircuitBreaker, newGateleenThriftyExceptionFactory(), true);
    }

    public QueueProcessor(
        Vertx vertx,
        HttpClient httpClient,
        MonitoringHandler monitoringHandler,
        QueueCircuitBreaker queueCircuitBreaker,
        GateleenExceptionFactory exceptionFactory,
        boolean immediatelyStartQueueProcessing
    ) {
        this.vertx = vertx;
        this.httpClient = httpClient;
        this.monitoringHandler = monitoringHandler;
        this.queueCircuitBreaker = queueCircuitBreaker;
        this.exceptionFactory = exceptionFactory;

        if (immediatelyStartQueueProcessing) {
            startQueueProcessing();
        } else {
            log.info("initialized QueueProcessor but queue processing has disabled");
        }
    }

    public void startQueueProcessing() {
        if (this.consumer == null || !this.consumer.isRegistered()) {
            log.info("about to register consumer to start queue processing");
            this.consumer = vertx.eventBus().consumer(getQueueProcessorAddress(), (Handler<Message<JsonObject>>) message -> {
                HttpRequest queuedRequestTry = null;
                JsonObject jsonRequest = new JsonObject(message.body().getString("payload"));
                try {
                    queuedRequestTry = new HttpRequest(jsonRequest);
                } catch (Exception exception) {
                    log.error("Could not build request: {} error is {}", message.body().toString(), exception.getMessage());
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
                    queueCircuitBreaker.handleQueuedRequest(queueName, queuedRequest).onComplete(event -> {
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
            log.info("registered queue processing consumer on address: {}", this.consumer.address());
        } else {
            log.info("queue processing is already started");
        }
    }

    public void stopQueueProcessing() {
        if (this.consumer != null && this.consumer.isRegistered()) {
            log.info("about to unregister consumer to stop queue processing");
            this.consumer.unregister();
        } else {
            log.info("queue processing is already stopped");
        }
    }

    public boolean isQueueProcessingStarted() {
        return this.consumer != null && this.consumer.isRegistered();
    }

    public String getQueueProcessorAddress() {
        return Address.queueProcessorAddress();
    }

    private boolean isCircuitCheckEnabled() {
        return queueCircuitBreaker != null && queueCircuitBreaker.isCircuitCheckEnabled();
    }

    private boolean isStatisticsUpdateEnabled() {
        return queueCircuitBreaker != null && queueCircuitBreaker.isStatisticsUpdateEnabled();
    }

    /**
     * <p>Answers if specified method is valid for queueing.</p>
     *
     * <p>{@link QueueProcessor} will only be able to deliver requests where
     * {@link HttpRequest#HttpRequest(JsonObject)} will NOT throw. Therefore
     * there's no sense to enqueue a request which we cannot process in
     * {@link #startQueueProcessing()}.</p>
     *
     * @return True if method is allowed to be enqueued. Not all methods are allowed
     * here because we deliver only whitelisted methods.
     */
    public static boolean httpMethodIsQueueable(HttpMethod method) {
        final boolean result;
        switch (method.name()) {
            // We accept those methods:
            case "GET":
            case "HEAD":
            case "PUT":
            case "POST":
            case "DELETE":
            case "OPTIONS":
            case "PATCH":
                result = true;
                break;
            // We do NOT accept all other methods.
            default:
                // This (default branch) gets reached when:
                // 1. Someone is using a (for us) unknown http method (eg someone added a new
                //    one in the enum since this here was written).
                // 2. We explicitly forbid CONNECT, TRACE and OTHER.
                //    See "https://github.com/swisspush/gateleen/issues/249".
                result = false;
        }
        return result;
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
            queueCircuitBreaker.updateStatistics(queueName, queuedRequest, queueResponseType).onComplete(event -> {
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
            queueCircuitBreaker.closeCircuit(queuedRequest).onComplete(event -> {
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
            queueCircuitBreaker.reOpenCircuit(queuedRequest).onComplete(event -> {
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
            logger.info("request expired to " + queuedRequest.getUri());
            message.reply(new JsonObject().put(STATUS, OK));
            return;
        }

        httpClient.request(queuedRequest.getMethod(), queuedRequest.getUri()).onComplete(asyncReqResult -> {
            if (asyncReqResult.failed()) {
                logger.warn("Failed request to {}: {}", queuedRequest.getUri(), asyncReqResult.cause());
                return;
            }
            HttpClientRequest request1 = asyncReqResult.result();
            if (queuedRequest.getHeaders() != null && !queuedRequest.getHeaders().isEmpty()) {
                request1.headers().setAll(queuedRequest.getHeaders());
            }

            request1.exceptionHandler(exception -> {
                logger.warn("Failed request to {}: {}", queuedRequest.getUri(), asyncReqResult.cause());
                message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, exception.getMessage()));
                performCircuitBreakerActions(queueName, queuedRequest, FAILURE, state);
            });

            Handler<AsyncResult<HttpClientResponse>> httpAsyncHandler = asyncResult -> {
                if (asyncResult.failed()) {
                    logger.error("TODO error handling", exceptionFactory.newException(
                        "httpClientRequest.send() failed", asyncResult.cause()));
                    return;
                }
                HttpClientResponse response = asyncResult.result();
                int statusCode = response.statusCode();
                logger.trace("response: {}", statusCode);
                if (statusCode >= 200 && statusCode < 300 || statusCode == 409) {
                    if (statusCode != StatusCode.CONFLICT.getStatusCode()) {
                        logger.debug("Successful request to {}", queuedRequest.getUri());
                    } else {
                        logger.warn("Ignoring request conflict to {}: {} {}", queuedRequest.getUri(), statusCode, response.statusMessage());
                    }
                    message.reply(new JsonObject().put(STATUS, OK));
                    performCircuitBreakerActions(queueName, queuedRequest, SUCCESS, state);
                    monitoringHandler.updateDequeue();
                } else if (QueueRetryUtil.retryQueueItem(queuedRequest.getHeaders(), statusCode, logger)) {
                    logger.info("Failed queued request to {}: {} {}", queuedRequest.getUri(), statusCode, response.statusMessage());
                    message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, statusCode + " " + response.statusMessage()));
                    performCircuitBreakerActions(queueName, queuedRequest, FAILURE, state);
                } else {
                    logger.info("Reply success, because no more retries left for failed queued request to {}: {} {}", queuedRequest.getUri(), statusCode, response.statusMessage());
                    message.reply(new JsonObject().put(STATUS, OK));
                    performCircuitBreakerActions(queueName, queuedRequest, SUCCESS, state);
                }
                response.handler(DEV_NULL);
                response.endHandler(nothing -> logger.debug("Backend response end"));
                response.exceptionHandler(exception -> {
                    logger.warn("Exception on response from {}: {}", queuedRequest.getUri(), exception.getMessage());
                    message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, exception.getMessage()));
                    performCircuitBreakerActions(queueName, queuedRequest, FAILURE, state);
                });
            };
            request1.idleTimeout(120000); // avoids blocking other requests
            if (queuedRequest.getPayload() != null) {
                vertx.<Buffer>executeBlocking(() -> {
                    long beginEpchMs = currentTimeMillis();
                    Buffer payload = buffer(queuedRequest.getPayload());
                    long durationMs = currentTimeMillis() - beginEpchMs;
                    if (durationMs > 16) logger.debug("Creating buffer of size {} took {}ms", payload.length(), durationMs);
                    return payload;
                }, false).compose((Buffer payload) -> {
                    request1.send(payload, httpAsyncHandler);
                    return succeededFuture();
                }).onFailure((Throwable ex) -> {
                    httpAsyncHandler.handle(failedFuture(ex));
                });
            } else {
                request1.send(httpAsyncHandler);
            }
        });

    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}
