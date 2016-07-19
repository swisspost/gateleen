package org.swisspush.gateleen.delegate;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a Delegate.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class Delegate {
    private static final Logger LOG = LoggerFactory.getLogger(Delegate.class);
    private static final String HEADERS = "headers";
    private static final String PAYLOAD = "payload";
    private static final String URI = "uri";
    private static final String METHOD = "method";
    private static final int FIRST = 0;
    private static final int STATUS_CODE_2XX = 2;

    private final String name;
    private final HttpClient selfClient;
    private final MonitoringHandler monitoringHandler;
    private final Pattern pattern;
    private final Set<HttpMethod> methods;
    private final List<JsonObject> requests;

    /**
     * Creates a new instance of a Delegate.
     *  @param monitoringHandler monitoringHandler
     * @param selfClient selfClient
     * @param name name of delegate
     * @param pattern pattern for the delegate
     * @param methods methods of the delegate
     * @param requests requests of the delegate
     */
    public Delegate(final MonitoringHandler monitoringHandler, final HttpClient selfClient, final String name, final Pattern pattern, final Set<HttpMethod> methods, final List<JsonObject> requests) {
        this.monitoringHandler = monitoringHandler;
        this.selfClient = selfClient;
        this.name = name;
        this.pattern = pattern;
        this.methods = methods;
        this.requests = requests;
    }

    /**
     * Returns the name of the delegate.
     *
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * Handles the given request.
     *
     * @param request original request
     */
    public void handle(final HttpServerRequest request) {


        // is method handled?
        if ( methods.contains(request.method())) {
            final Handler<HttpClientResponse> handler = installDoneHandler(request);
            final JsonObject firstRequest = requests.get(FIRST);
            createRequest(request.uri(), firstRequest, handler);
            return;
        }

        // end response, if nothing matches
        request.response().end();
    }

    /**
     * Prepares and fires a delegate request. <br>
     * The preparation includes the replacement of all groups
     * matching the given delegate pattern. Also the
     * request uri is adapted.
     *
     * @param uri original request
     * @param requestObject the delegate request object
     * @param doneHandler the done handler called as soon as the request is executed
     */
    private void createRequest(final String uri, final JsonObject requestObject, final Handler<HttpClientResponse> doneHandler) {
        // matcher to replace wildcards with matching groups
        final Matcher matcher = pattern.matcher(uri);

        // adapt the request uri if necessary
        final String requestUri = matcher.replaceAll(requestObject.getString(URI));

        // get the string represantion of the payload object
        String payloadStr;
        try {
            payloadStr = requestObject.getString(PAYLOAD);
        } catch(ClassCastException e) {
            payloadStr = requestObject.getJsonObject(PAYLOAD).encode();
        }

        // replacement of matching groups
        if(payloadStr != null) {
            payloadStr = matcher.replaceAll(payloadStr);
        }

        // headers of the delegate
        MultiMap headers = new CaseInsensitiveHeaders();
        JsonArray headersArray = requestObject.getJsonArray(HEADERS);
        if ( headersArray != null ) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Request headers:");
            }

            headersArray.forEach(header -> {
                if (LOG.isTraceEnabled()) {
                    LOG.trace(" > Key [{}], Value [{}]", ((JsonArray) header).getString(0), ((JsonArray) header).getString(1) );
                }

                headers.add(((JsonArray) header).getString(0),((JsonArray) header).getString(1));

            });
        }

        HttpClientRequest delegateRequest = selfClient.request(HttpMethod.valueOf(requestObject.getString(METHOD)), requestUri, doneHandler);
        delegateRequest.headers().setAll(headers);
        delegateRequest.exceptionHandler(exception -> LOG.warn("Delegate request {} failed: {}",requestUri , exception.getMessage()));
        delegateRequest.setTimeout(120000); // avoids blocking other requests
        delegateRequest.end(Buffer.buffer(payloadStr));
    }

    /**
     * Creates a new DoneHandler for the given request.
     *
     * @param request the original request
     * @return a doneHandler
     */
    private Handler<HttpClientResponse> installDoneHandler(final HttpServerRequest request) {
        return new Handler<HttpClientResponse>() {
            private AtomicInteger currentIndex = new AtomicInteger(0);

            @Override
            public void handle(HttpClientResponse response) {
                if ( LOG.isTraceEnabled() ) {
                   LOG.trace("Done handler - handle");
                }

                // request was fine
                if ( ( response.statusCode() / 100 ) == STATUS_CODE_2XX  ) {
                    if ( LOG.isTraceEnabled() ) {
                        LOG.trace("Done handler - OK");
                    }

                    // is there another request?
                    if ( currentIndex.incrementAndGet() < requests.size() ) {
                        if ( LOG.isTraceEnabled() ) {
                            LOG.trace("Done handler - calling next {}", currentIndex.get());
                        }

                        final JsonObject delegateRequest = requests.get(currentIndex.get());
                        createRequest(request.uri(), delegateRequest, this );
                    }
                    // if not, send corresponding respond
                    else {
                        if ( LOG.isTraceEnabled() ) {
                        LOG.trace("Done handler - not 2XX, create response [{}]", response.statusCode());
                        }
                        createResponse(request, response);
                    }
                }
                // request failed
                else {
                    if ( LOG.isTraceEnabled() ) {
                        LOG.trace("Done handler - not 200/202, create response [{}]", response.statusCode());
                    }
                    createResponse(request, response);
                }
            }
        };
    }

    /**
     * Create a response.
     *
     * @param request original request
     * @param response a response
     */
    private void createResponse(final HttpServerRequest request, final HttpClientResponse response) {
        request.response().setStatusCode(response.statusCode());
        request.response().setStatusMessage(response.statusMessage());
        request.response().setChunked(true);
        request.response().headers().addAll(response.headers());
        request.response().headers().remove("Content-Length");
        response.handler(data -> request.response().write(data));
        response.endHandler(v -> request.response().end());
    }
}
