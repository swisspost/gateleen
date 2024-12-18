package org.swisspush.gateleen.queue.queuing.circuitbreaker.api;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreaker;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.PatternAndCircuitHash;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;

import static org.swisspush.gateleen.queue.queuing.circuitbreaker.api.QueueCircuitBreakerAPI.*;

/**
 * Handles {@link QueueCircuitBreaker} related http requests. Provides access to the following
 * {@link QueueCircuitBreaker} related informations through http requests:
 * <ul>
 * <li>Get informations of all circuits</li>
 * <li>Get informations of a single circuit</li>
 * <li>Change states of all circuits</li>
 * <li>Change status of a single circuit</li>
 * </ul>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerHttpRequestHandler implements Handler<HttpServerRequest> {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreakerHttpRequestHandler.class);

    private Router router;

    private final String allPrefix = "_all";
    private final String statusSuffix = "/status";
    private final String circuitIdParam = "/:circuitId";

    private EventBus eventBus;
    private QueueCircuitBreakerStorage storage;

    public static final String HTTP_REQUEST_API_ADDRESS = "gateleen.queue-circuit-breaker.http-request-api";

    private static final String APPLICATION_JSON = "application/json";
    private static final String CONTENT_TYPE = "content-type";

    public QueueCircuitBreakerHttpRequestHandler(Vertx vertx, QueueCircuitBreakerStorage storage, String prefix) {
        this.router = Router.router(vertx);
        this.eventBus = vertx.eventBus();
        this.storage = storage;

        registerAPIConsumer();

        // list all circuits
        router.get(prefix).handler(ctx -> {
            log(ctx.request(), "list all circuits");
            handleGetAllCircuitsRequest(ctx);
        });

        // list all circuits
        router.get(prefix + "/" + allPrefix).handler(ctx -> {
            log(ctx.request(), "list all circuits");
            handleGetAllCircuitsRequest(ctx);
        });

        // change all circuit states
        router.putWithRegex(prefix + "/" + allPrefix + statusSuffix).handler(ctx -> ctx.request().bodyHandler(event -> {
            QueueCircuitState state = extractStatusFromBody(event);
            log(ctx.request(), "change all circuit states to " + state);
            if (state == null) {
                respondWith(StatusCode.BAD_REQUEST, "Body must contain a correct 'status' value", ctx.request());
            } else if (QueueCircuitState.CLOSED != state) {
                respondWith(StatusCode.FORBIDDEN, "Status can be changed to 'CLOSED' only", ctx.request());
            } else {
                eventBus.request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildCloseAllCircuitsOperation(),
                        (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                            if (reply.succeeded()) {
                                JsonObject replyBody = reply.result().body();
                                if (OK.equals(replyBody.getString(STATUS))) {
                                    ctx.response().end();
                                } else {
                                    ctx.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                                    ctx.response().end(reply.result().body().getString(MESSAGE));
                                }
                            } else {
                                ctx.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                                ctx.response().end(reply.cause().getMessage());
                            }
                        });
            }
        }));

        // get all circuit states
        router.getWithRegex(prefix + "/" + allPrefix + statusSuffix).handler(ctx -> respondWith(StatusCode.METHOD_NOT_ALLOWED, ctx.request()));

        // get single circuit status
        router.get(prefix + circuitIdParam + statusSuffix).handler(ctx -> {
            String circuitId = extractCircuitId(ctx);
            log(ctx.request(), "get status of circuit " + circuitId);
            eventBus.request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildGetCircuitStatusOperation(circuitId),
                    (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                        if (reply.succeeded()) {
                            JsonObject replyBody = reply.result().body();
                            if (OK.equals(replyBody.getString(STATUS))) {
                                jsonResponse(ctx.response(), replyBody.getJsonObject(VALUE));
                            } else {
                                ctx.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                                ctx.response().end(reply.result().body().getString(MESSAGE));
                            }
                        } else {
                            ctx.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                            ctx.response().end(reply.cause().getMessage());
                        }
                    });
        });

        // change single circuit status
        router.put(prefix + circuitIdParam + statusSuffix).handler(ctx -> {
            String circuitId = extractCircuitId(ctx);
            ctx.request().bodyHandler(event -> {
                QueueCircuitState state = extractStatusFromBody(event);
                log(ctx.request(), "change status of circuit " + circuitId + " to " + state);
                if (state == null) {
                    respondWith(StatusCode.BAD_REQUEST, "Body must contain a correct 'status' value", ctx.request());
                } else if (QueueCircuitState.CLOSED != state) {
                    respondWith(StatusCode.FORBIDDEN, "Status can be changed to 'CLOSED' only", ctx.request());
                } else {
                    eventBus.request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildCloseCircuitOperation(circuitId),
                            (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                                if (reply.succeeded()) {
                                    JsonObject replyBody = reply.result().body();
                                    if (OK.equals(replyBody.getString(STATUS))) {
                                        ctx.response().end();
                                    } else {
                                        ctx.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                                        ctx.response().end(reply.result().body().getString(MESSAGE));
                                    }
                                } else {
                                    ctx.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                                    ctx.response().end(reply.cause().getMessage());
                                }
                            });
                }
            });
        });

        // get single circuit
        router.get(prefix + circuitIdParam).handler(ctx -> {
            String circuitId = extractCircuitId(ctx);
            log(ctx.request(), "get information of circuit " + circuitId);
            eventBus.request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildGetCircuitInformationOperation(circuitId),
                    (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                        if (reply.succeeded()) {
                            JsonObject replyBody = reply.result().body();
                            if (OK.equals(replyBody.getString(STATUS))) {
                                jsonResponse(ctx.response(), replyBody.getJsonObject(VALUE));
                            } else {
                                ctx.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                                ctx.response().end(reply.result().body().getString(MESSAGE));
                            }
                        } else {
                            ctx.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                            ctx.response().end(reply.cause().getMessage());
                        }
                    });
        });

        router.routeWithRegex(".*").handler(ctx -> respondWith(StatusCode.METHOD_NOT_ALLOWED, ctx.request()));
    }

    @Override
    public void handle(HttpServerRequest request) {
        router.handle(request);
    }

    private void log(HttpServerRequest request, String logMessage) {
        RequestLoggerFactory.getLogger(QueueCircuitBreakerHttpRequestHandler.class, request).info(logMessage);
    }

    private void respondWith(StatusCode statusCode, String responseMessage, HttpServerRequest request) {
        log(request, "Responding with status code " + statusCode + " and message: " + responseMessage);
        request.response().setStatusCode(statusCode.getStatusCode());
        request.response().setStatusMessage(statusCode.getStatusMessage());
        request.response().end(responseMessage);
    }

    private void respondWith(StatusCode statusCode, HttpServerRequest request) {
        respondWith(statusCode, statusCode.getStatusMessage(), request);
    }

    private QueueCircuitState extractStatusFromBody(Buffer bodyBuffer) {
        if (StringUtils.isNotEmptyTrimmed(bodyBuffer.toString())) {
            try {
                JsonObject obj = bodyBuffer.toJsonObject();
                return QueueCircuitState.fromString(obj.getString(STATUS), null);
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    private void jsonResponse(HttpServerResponse response, JsonObject object) {
        response.putHeader(CONTENT_TYPE, APPLICATION_JSON);
        response.end(object.encode());
    }

    private String extractCircuitId(RoutingContext ctx) {
        return ctx.request().getParam("circuitId");
    }

    private void handleGetAllCircuitsRequest(RoutingContext ctx) {
        eventBus.request(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildGetAllCircuitsOperation(),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    if (reply.succeeded()) {
                        JsonObject replyBody = reply.result().body();
                        if (OK.equals(replyBody.getString(STATUS))) {
                            jsonResponse(ctx.response(), replyBody.getJsonObject(VALUE));
                        } else {
                            ctx.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                            ctx.response().end(reply.result().body().getString(MESSAGE));
                        }
                    } else {
                        ctx.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
                        ctx.response().end(reply.cause().getMessage());
                    }
                });
    }

    private void registerAPIConsumer() {
        eventBus.localConsumer(QueueCircuitBreakerHttpRequestHandler.HTTP_REQUEST_API_ADDRESS, (Handler<Message<JsonObject>>) event -> {
            String opString = event.body().getString(OPERATION);
            Operation operation = Operation.fromString(opString);
            if (operation == null) {
                unsupportedOperation(opString, event);
                return;
            }

            switch (operation) {
                case getCircuitInformation:
                    handleGetCircuitInformation(event);
                    break;
                case getCircuitStatus:
                    handleGetCircuitStatus(event);
                    break;
                case closeCircuit:
                    handleCloseCircuit(event);
                    break;
                case closeAllCircuits:
                    handleCloseAllCircuits(event);
                    break;
                case getAllCircuits:
                    handleGetAllCircuits(event);
                    break;
                default:
                    unsupportedOperation(opString, event);
            }
        });
    }

    private void handleGetCircuitInformation(Message<JsonObject> message) {
        String circuitHash = message.body().getJsonObject(PAYLOAD).getString(CIRCUIT_HASH);
        storage.getQueueCircuitInformation(circuitHash).onComplete(event -> {
            if (event.failed()) {
                message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, event.cause().getMessage()));
                return;
            }
            message.reply(new JsonObject().put(STATUS, OK).put(VALUE, event.result()));
        });
    }

    private void handleGetCircuitStatus(Message<JsonObject> message) {
        String circuitHash = message.body().getJsonObject(PAYLOAD).getString(CIRCUIT_HASH);
        storage.getQueueCircuitState(circuitHash).onComplete(event -> {
            if (event.failed()) {
                message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, event.cause().getMessage()));
                return;
            }
            message.reply(new JsonObject().put(STATUS, OK).put(VALUE, new JsonObject().put("status", event.result().name().toLowerCase())));
        });
    }

    private void handleCloseCircuit(Message<JsonObject> message) {
        String circuitHash = message.body().getJsonObject(PAYLOAD).getString(CIRCUIT_HASH);
        PatternAndCircuitHash patternAndCircuitHash = new PatternAndCircuitHash(null, circuitHash, null);
        storage.closeCircuit(patternAndCircuitHash).onComplete(event -> {
            if (event.failed()) {
                message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, event.cause().getMessage()));
                return;
            }
            message.reply(new JsonObject().put(STATUS, OK));
        });
    }

    private void handleCloseAllCircuits(Message<JsonObject> message) {
        storage.closeAllCircuits().onComplete(event -> {
            if (event.failed()) {
                message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, event.cause().getMessage()));
                return;
            }
            message.reply(new JsonObject().put(STATUS, OK));
        });
    }

    private void handleGetAllCircuits(Message<JsonObject> message) {
        storage.getAllCircuits().onComplete(event -> {
            if (event.failed()) {
                message.reply(new JsonObject().put(STATUS, ERROR).put(MESSAGE, event.cause().getMessage()));
                return;
            }
            message.reply(new JsonObject().put(STATUS, OK).put(VALUE, event.result()));
        });
    }

    private void unsupportedOperation(String operation, Message<JsonObject> event) {
        JsonObject reply = new JsonObject();
        String message = "Unsupported operation received: " + operation;
        log.error(message);
        reply.put(STATUS, ERROR);
        reply.put(MESSAGE, message);
        event.reply(reply);
    }
}
