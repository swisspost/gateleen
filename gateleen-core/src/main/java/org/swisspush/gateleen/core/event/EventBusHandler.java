package org.swisspush.gateleen.core.event;

import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.json.JsonMultiMap;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Delivers a request on the event bus.
 * <p>
 * If the header "x-sync" is set to "true", then the handler waits for an event reply before returning the response.
 * In this case, the response contains the replied JSON, if any.
 * </p>
 * <p>
 * Requests are forwarded in the form:
 * 
 * <pre>
 * {
 *     "uri": "http://...",
 *     "method": "PUT",
 *     "headers": [
 *          [ "name", "value" ],
 *          [ "name", "value" ]
 *     ],
 *     "payload": {
 *         "hello": "world"
 *     }
 * }
 * </pre>
 *
 * Payload is a JsonObject in case of application/json content type, a string in case of text/* content type, a
 * base64 binary string otherwise.
 * <p>
 * Replies from clients have the same structure, without the uri.1
 * </p>
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class EventBusHandler {

    public static final int ACCEPTED = 202;
    public static final String SYNC = "x-sync";
    public static final String TEXT = "text/";
    public static final String METHOD = "method";
    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final int BAD_REQUEST = 400;
    public static final String CONTENT_TYPE = "content-type";
    public static final String APPLICATION_JSON = "application/json";
    public static final String PAYLOAD = "payload";
    public static final String URI = "uri";
    public static final String HEADERS = "headers";
    public static final int TIMEOUT = 20000;
    public static final int GATEWAY_TIMEOUT = 504;
    private Vertx vertx;
    private String apiPath;
    private String sockPath;
    private String addressPrefix;
    private Pattern adressPathPattern;
    private Long eventbusBridgePingInterval = null;

    /**
     * Constructs and configures the handler.
     * 
     * @param vertx vertx
     * @param apiPath The full URI path where to access the event api. E.g. <code>/context/server/event/v1/</code>.
     * @param sockPath The full URI path of the SockJS endpoint to configure.
     * @param addressPrefix The prefix used for the event bus addresses the handlers sends requests to. E.g. <code>event/</code>
     * @param addressPathPattern A pattern to extract the address from the URI. This pattern is appended to the apiPath and must have a group.
     * For example, a pattern <code>hello/(world/[^/]+)/.*</code> will forward requests on
     * <code>/context/server/event/v1/hello/world/foo/bar</code> to address <code>event/world/foo</code>.
     */
    public EventBusHandler(Vertx vertx, String apiPath, String sockPath, String addressPrefix, String addressPathPattern) {
        this.vertx = vertx;
        this.apiPath = apiPath;
        this.sockPath = sockPath;
        this.addressPrefix = addressPrefix;
        this.adressPathPattern = Pattern.compile(apiPath + addressPathPattern);
    }

    public boolean handle(final HttpServerRequest request) {
        final Logger requestLog = RequestLoggerFactory.getLogger(EventBusHandler.class, request);
        if (request.uri().startsWith(apiPath)) {
            requestLog.debug("Handling {}", request.uri());
            Matcher matcher = adressPathPattern.matcher(request.uri());
            if (matcher.matches()) {
                final String address = addressPrefix + matcher.group(1);
                final JsonObject message = new JsonObject().put(URI, request.uri()).put(METHOD, request.method()).put(HEADERS, JsonMultiMap.toJson(request.headers()));
                requestLog.debug("Preparing message for address {}", address);
                request.bodyHandler(new Handler<Buffer>() {
                    @Override
                    public void handle(Buffer buffer) {
                        String contentType = request.headers().get(CONTENT_TYPE);
                        if (contentType == null) {
                            contentType = APPLICATION_JSON;
                        }
                        if (buffer != null && buffer.length() > 0) {
                            if (contentType.contains(APPLICATION_JSON)) {
                                try {
                                    message.put(PAYLOAD, new JsonObject(buffer.toString()));
                                } catch (DecodeException e) {
                                    request.response().setStatusCode(BAD_REQUEST);
                                    request.response().end(e.getMessage());
                                    return;
                                }
                            } else if (contentType.contains(TEXT)) {
                                message.put(PAYLOAD, buffer.toString());
                            } else {
                                message.put(PAYLOAD, buffer.getBytes());
                            }
                        }
                        requestLog.debug("Request content type is {}", contentType);
                        if (HttpMethod.GET == request.method() || Boolean.TRUE.toString().equals(request.headers().get(SYNC))) {
                            requestLog.debug("This is a synchronous request");
                            vertx.eventBus().send(address, message, new DeliveryOptions().setSendTimeout(TIMEOUT), new Handler<AsyncResult<Message<JsonObject>>>() {
                                @Override
                                public void handle(AsyncResult<Message<JsonObject>> reply) {
                                    if (reply.succeeded()) {
                                        requestLog.debug("Got response");
                                        JsonObject response = reply.result().body();
                                        MultiMap headers = null;
                                        try {
                                            if (response.fieldNames().contains(HEADERS)) {
                                                headers = JsonMultiMap.fromJson(response.getJsonArray(HEADERS));
                                                request.response().headers().setAll(headers);
                                            }
                                        } catch (DecodeException e) {
                                            log.warn("Wrong headers in reply", e);
                                        }
                                        if (response.fieldNames().contains(PAYLOAD)) {
                                            String responseContentType;
                                            if (headers != null) {
                                                responseContentType = headers.get(CONTENT_TYPE);
                                            } else {
                                                responseContentType = APPLICATION_JSON;
                                            }
                                            requestLog.debug("Response content type is {}", responseContentType);
                                            try {
                                                if (responseContentType.contains(APPLICATION_JSON)) {
                                                    request.response().end(response.getJsonObject(PAYLOAD).encode());
                                                } else if (responseContentType.contains(TEXT)) {
                                                    request.response().end(response.getString(PAYLOAD));
                                                } else {
                                                    request.response().end(Buffer.buffer(response.getBinary(PAYLOAD)));
                                                }
                                            } catch (DecodeException e) {
                                                log.warn("Wrong payload in reply for content-type " + responseContentType, e);
                                                request.response().setStatusCode(500);
                                                request.response().end("Wrong payload in reply for content-type " + responseContentType + ": ", e.getMessage());
                                            }
                                        } else {
                                            requestLog.debug("No payload in response");
                                            request.response().end();
                                        }
                                    } else {
                                        requestLog.debug("Timeout");
                                        request.response().setStatusCode(GATEWAY_TIMEOUT);
                                        request.response().end("Gateway Timeout");
                                    }
                                }
                            });
                        } else {
                            log.debug("This is an asynchronous request");
                            vertx.eventBus().publish(address, message);
                            request.response().setStatusCode(ACCEPTED);
                            request.response().end();
                        }
                    }
                });
                return true;
            }
        }
        return false;
    }

    /**
     * Configures and binds the SockJS bridge to an HttpServer.
     * 
     * @param router router
     */
    public void install(Router router) {
        BridgeOptions bridgeOptions = new BridgeOptions()
                .addOutboundPermitted(new PermittedOptions().setAddressRegex(addressPrefix + "(.*)"));

        if (eventbusBridgePingInterval != null) {
            bridgeOptions = bridgeOptions.setPingTimeout(eventbusBridgePingInterval);
        }
        router.route(sockPath+"/*").handler(SockJSHandler.create(vertx).bridge(bridgeOptions));
        log.info("Installed SockJS endpoint on " + sockPath);
        log.info("Listening to requests on " + adressPathPattern.pattern());
        log.info("Using address prefix " + addressPrefix);
    }

    /**
     * Sets the ping_interval passed to the eventbus bridge. Defines the interval before a websocket connection is closed if no interaction with client happens in the meantime.
     * Set the interval before calling {@link #install(io.vertx.ext.web.Router)}
     * 
     * @param eventbusBridgePingInterval Interval in milliseconds or null to use the default interval of the eventbus bridge (10 seconds)
     */
    public void setEventbusBridgePingInterval(Long eventbusBridgePingInterval) {
        this.eventbusBridgePingInterval = eventbusBridgePingInterval;
    }
}
