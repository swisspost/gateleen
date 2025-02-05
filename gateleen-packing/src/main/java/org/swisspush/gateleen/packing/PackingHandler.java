package org.swisspush.gateleen.packing;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.Result;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.packing.validation.PackingValidator;
import org.swisspush.gateleen.queue.expiry.ExpiryCheckHandler;
import org.swisspush.gateleen.queue.queuing.QueueClient;
import org.swisspush.gateleen.queue.queuing.QueuingHandler;

import java.util.List;

import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * Extract requests from a packing request and forward them to a handler.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class PackingHandler {

    public static final String PACK_HEADER = "x-packed";

    private final Vertx vertx;
    private final String redisquesAddress;
    private final String queuePrefix;
    private final PackingValidator validator;
    private final GateleenExceptionFactory exceptionFactory;

    public PackingHandler(Vertx vertx, String queuePrefix, String redisquesAddress, PackingValidator validator, GateleenExceptionFactory exceptionFactory) {
        this.vertx = vertx;
        this.queuePrefix = queuePrefix;
        this.redisquesAddress = redisquesAddress;
        this.validator = validator;
        this.exceptionFactory = exceptionFactory;
    }

    public boolean isPacked(HttpServerRequest request) {
        return request.headers().get(PACK_HEADER) != null;
    }

    public boolean handle(final HttpServerRequest request) {
        Logger requestLog = RequestLoggerFactory.getLogger(PackingHandler.class, request);
        if(!isPacked(request)) {
            requestLog.warn("Request is not packed and should not be handled by the PackingHandler");
            respondWith(request, StatusCode.INTERNAL_SERVER_ERROR);
            return true;
        }
        if(HttpMethod.PUT != request.method() && HttpMethod.POST != request.method()) {
            requestLog.warn("Only PUT/POST requests allowed for packed requests");
            respondWith(request, StatusCode.BAD_REQUEST);
            return true;
        }

        String fallbackQueueNameSuffix = String.valueOf(System.currentTimeMillis());

        request.bodyHandler(payload -> {
            ValidationResult validationResult = validator.validatePackingPayload(payload);
            if(!validationResult.isSuccess()) {
                requestLog.warn("Invalid packing payload: " + validationResult.getMessage());
                respondWith(request, StatusCode.BAD_REQUEST);
                return;
            }

            Result<List<HttpRequest>, String> parseRequestsResult = PackingRequestParser.parseRequests(payload);
            if(parseRequestsResult.isErr()) {
                requestLog.warn("Error while parsing requests from packing payload: " + parseRequestsResult.err());
                respondWith(request, StatusCode.BAD_REQUEST);
                return;
            }

            for (HttpRequest req : parseRequestsResult.ok()) {
                if (req.getHeaders() != null) {
                    req.getHeaders().remove(ExpiryCheckHandler.SERVER_TIMESTAMP_HEADER);
                }

                ExpiryCheckHandler.updateServerTimestampHeader(req);

                String queueName = getQueueFromRequestOrPrefix(req, fallbackQueueNameSuffix);
                JsonObject enqueOp = buildEnqueueOperation(queueName, req.toJsonObject().put(QueueClient.QUEUE_TIMESTAMP, System.currentTimeMillis()).encode());
                vertx.eventBus().request(redisquesAddress, enqueOp, (Handler<AsyncResult<Message<JsonObject>>>) event -> {
                    if (event.failed()) {
                        requestLog.error("Could not enqueue request {}", req.toJsonObject().encodePrettily());
                        if (requestLog.isWarnEnabled()) {
                            requestLog.warn("Could not enqueue request '{}' '{}'", queueName, req.getUri(),
                                    exceptionFactory.newException("eventBus.request('" + redisquesAddress + "', enqueOp) failed", event.cause()));
                        }
                        return;
                    }
                    if (!OK.equals(event.result().body().getString(STATUS))) {
                        requestLog.error("Could not enqueue request {}", req.toJsonObject().encodePrettily());
                    }
                });
            }

            respondWith(request, StatusCode.OK);
        });

        return true;
    }

    private String getQueueFromRequestOrPrefix(HttpRequest request, String fallbackQueueNameSuffix) {
        if(request.getHeaders().contains(QueuingHandler.QUEUE_HEADER)){
            return request.getHeaders().get(QueuingHandler.QUEUE_HEADER);
        }
        return queuePrefix + fallbackQueueNameSuffix;
    }

    private void respondWith(HttpServerRequest request, StatusCode statusCode) {
        ResponseStatusCodeLogUtil.info(request, statusCode, PackingHandler.class);
        request.response().setStatusCode(statusCode.getStatusCode());
        request.response().setStatusMessage(statusCode.getStatusMessage());
        request.response().end();
    }
}
