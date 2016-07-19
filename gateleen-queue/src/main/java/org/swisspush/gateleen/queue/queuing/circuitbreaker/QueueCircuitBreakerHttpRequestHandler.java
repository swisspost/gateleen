package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.swisspush.gateleen.core.util.StatusCode;

import static org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerAPI.*;

/**
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

    public QueueCircuitBreakerHttpRequestHandler(Vertx vertx, QueueCircuitBreakerStorage storage, String prefix){
        this.router = Router.router(vertx);
        this.eventBus = vertx.eventBus();
        this.storage = storage;

        registerAPIConsumer();

        // list all circuits
        router.get(prefix).handler(ctx ->{
            ctx.response().end();
        });

        // list all circuits
        router.get(prefix + "/" + allPrefix).handler(ctx ->{
            ctx.response().end();
        });

        // change all circuit states
        router.putWithRegex(prefix + "/" + allPrefix + statusSuffix).handler(ctx ->{
            ctx.response().end();
        });

        // get all circuit states
        router.getWithRegex(prefix + "/" + allPrefix + statusSuffix).handler(ctx ->{
            ctx.response().end();
        });

        // get single circuit status
        router.get(prefix + circuitIdParam + statusSuffix).handler(ctx ->{
            String circuitId = extractCircuitId(ctx);
            eventBus.send(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildGetCircuitStatusOperation(circuitId),
                    new Handler<AsyncResult<Message<JsonObject>>>() {
                @Override
                public void handle(AsyncResult<Message<JsonObject>> reply) {
                    JsonObject replyBody = reply.result().body();
                    if (OK.equals(replyBody.getString(STATUS))) {
                        jsonResponse(ctx.response(), replyBody.getJsonObject(VALUE));
                    } else {
                        ctx.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                        ctx.response().end(reply.result().body().getString(MESSAGE));
                    }
                }
            });
        });

        // change single circuit status
        router.put(prefix + circuitIdParam + statusSuffix).handler(ctx ->{
            String circuitId = extractCircuitId(ctx);
            System.out.println("*** status ***");
            ctx.response().end();
        });

        // get single circuit
        router.get(prefix + circuitIdParam).handler(ctx ->{
            String circuitId = extractCircuitId(ctx);
            eventBus.send(HTTP_REQUEST_API_ADDRESS, QueueCircuitBreakerAPI.buildGetCircuitInformationOperation(circuitId),
                    new Handler<AsyncResult<Message<JsonObject>>>() {
                        @Override
                        public void handle(AsyncResult<Message<JsonObject>> reply) {
                            JsonObject replyBody = reply.result().body();
                            if (OK.equals(replyBody.getString(STATUS))) {
                                jsonResponse(ctx.response(), replyBody.getJsonObject(VALUE));
                            } else {
                                ctx.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                                ctx.response().end(reply.result().body().getString(MESSAGE));
                            }
                        }
                    });
        });

        router.routeWithRegex(".*").handler(ctx -> respondWithNotAllowed(ctx.request()));
    }

    @Override
    public void handle(HttpServerRequest request) { router.accept(request); }

    private void respondWithNotAllowed(HttpServerRequest request) {
        request.response().setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
        request.response().setStatusMessage(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
        request.response().end(StatusCode.METHOD_NOT_ALLOWED.toString());
    }

    private void jsonResponse(HttpServerResponse response, JsonObject object) {
        response.putHeader(CONTENT_TYPE, APPLICATION_JSON);
        response.end(object.encode());
    }

    private String extractCircuitId(RoutingContext ctx){
        return ctx.request().getParam("circuitId");
    }

    private void registerAPIConsumer(){
        eventBus.localConsumer(QueueCircuitBreakerHttpRequestHandler.HTTP_REQUEST_API_ADDRESS, new Handler<Message<JsonObject>>(){

            @Override
            public void handle(Message<JsonObject> event) {
                String opString = event.body().getString(OPERATION);
                Operation operation = Operation.fromString(opString);
                if(operation == null){
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
                    default:
                        unsupportedOperation(opString, event);
                }
            }
        });
    }

    private void handleGetCircuitInformation(Message<JsonObject> message){
        String circuitHash = message.body().getJsonObject(PAYLOAD).getString(CIRCUIT_HASH);
        storage.getQueueCircuitInformation(circuitHash).setHandler(event -> {
            if(event.failed()){
                message.reply(new JsonObject().put(STATUS, ERROR));
                return;
            }
            message.reply(new JsonObject().put(STATUS, OK).put(VALUE, event.result()));
        });
    }

    private void handleGetCircuitStatus(Message<JsonObject> message){
        String circuitHash = message.body().getJsonObject(PAYLOAD).getString(CIRCUIT_HASH);
        storage.getQueueCircuitState(circuitHash).setHandler(event -> {
            if(event.failed()){
                message.reply(new JsonObject().put(STATUS, ERROR));
                return;
            }
            message.reply(new JsonObject().put(STATUS, OK).put(VALUE, new JsonObject().put("status", event.result().name())));
        });
    }

    private void unsupportedOperation(String operation, Message<JsonObject> event){
        JsonObject reply = new JsonObject();
        String message = "Unsupported operation received: " + operation;
        log.error(message);
        reply.put(STATUS, ERROR);
        reply.put(MESSAGE, message);
        event.reply(reply);
    }
}
