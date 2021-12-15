package org.swisspush.gateleen.delegate;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.headers.VertxHttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.ClientRequestCreator;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.json.transform.JoltTransformer;
import org.swisspush.gateleen.core.util.HttpServerRequestUtil;
import org.swisspush.gateleen.core.util.StatusCode;

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
    private final ClientRequestCreator clientRequestCreator;
    private final Pattern pattern;
    private final Set<HttpMethod> methods;
    private final List<DelegateRequest> requests;
    private boolean delegateContainsJoltSpecRequest = false;

    /**
     * Creates a new instance of a Delegate.
     *
     * @param clientRequestCreator          selfClient
     * @param name                          name of delegate
     * @param pattern                       pattern for the delegate
     * @param methods                       methods of the delegate
     * @param requests                      requests of the delegate
     */
    public Delegate(final ClientRequestCreator clientRequestCreator, final String name, final Pattern pattern, final Set<HttpMethod> methods, final List<DelegateRequest> requests) {
        this.clientRequestCreator = clientRequestCreator;
        this.name = name;
        this.pattern = pattern;
        this.methods = methods;
        this.requests = requests;

        this.delegateContainsJoltSpecRequest = doesDelegateContainJoltSpecRequest();
    }

    /**
     * Returns the name of the delegate.
     *
     * @return name
     */
    public String getName() {
        return name;
    }

    List<DelegateRequest> getDelegateRequests() {
        return requests;
    }

    /**
     * Handles the given request.
     *
     * @param request original request
     */
    public void handle(final HttpServerRequest request) {
        // is method handled?
        if (methods.contains(request.method())) {

            // check if Pattern matches the given request url
            Matcher matcher = pattern.matcher(request.uri());
            if (matcher.matches()) {
                extractDelegateExecutionRequestJsonPayload(request).setHandler(payload -> {
                    if(payload.failed()){
                        String message = "Unable to parse payload of delegate execution request. " +
                                "When a delegate definition with a 'transformation' spec is defined, a valid json payload is required!";
                        LOG.warn(message);
                        request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                        request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                        request.response().end(message);
                        return;
                    }
                    final DelegateRequest firstDelegateRequest = requests.get(FIRST);
                    final Handler<HttpClientResponse> handler = installDoneHandler(request, payload.result());
                    createRequest(request, payload.result(), firstDelegateRequest, handler);
                });
                return;
            }
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
     * @param originalRequest  original request
     * @param requestContainer the container holding the request object and optionally a json transform spec
     * @param doneHandler      the done handler called as soon as the request is executed
     */
    private void createRequest(final HttpServerRequest originalRequest, final String delegateExecutionRequestJsonPayload, final DelegateRequest requestContainer, final Handler<HttpClientResponse> doneHandler) {

        // matcher to replace wildcards with matching groups
        final Matcher matcher = pattern.matcher(originalRequest.uri());

        generatePayload(delegateExecutionRequestJsonPayload, originalRequest.headers(), requestContainer, matcher).setHandler(payloadBuffer -> {

            if(payloadBuffer.failed()){
                String message = "Unable to generate delegate request payload. Cause: " + payloadBuffer.cause().getClass().getName();
                LOG.warn(message);
                originalRequest.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                originalRequest.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                originalRequest.response().end(message);
                return;
            }

            final JsonObject requestObject = requestContainer.getRequest();

            // adapt the request uri if necessary
            final String requestUri = matcher.replaceAll(requestObject.getString(URI));

            // headers of the delegate
            VertxHttpHeaders headers = createRequestHeaders(requestContainer, originalRequest.headers());

            HttpClientRequest delegateRequest = clientRequestCreator.createClientRequest(
                    HttpMethod.valueOf(requestObject.getString(METHOD)),
                    requestUri,
                    headers,
                    120000,
                    doneHandler,
                    exception -> LOG.warn("Delegate request {} failed: {}", requestUri, exception.getMessage())
            );

            Buffer buf = payloadBuffer.result();
            if (buf != null) {
                delegateRequest.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(buf.length()));
                delegateRequest.end(buf);
            } else {
                delegateRequest.end();
            }
        });
    }

    private VertxHttpHeaders createRequestHeaders(DelegateRequest requestContainer, MultiMap originalRequestHeaders){
        VertxHttpHeaders headers = new VertxHttpHeaders();

        JsonArray headersArray = requestContainer.getRequest().getJsonArray(HEADERS);

        // headers definition?
        if(headersArray != null){
            if (LOG.isTraceEnabled()) {
                LOG.trace("Request headers:");
            }

            headersArray.forEach(header -> {
                if (LOG.isTraceEnabled()) {
                    LOG.trace(" > Key [{}], Value [{}]", ((JsonArray) header).getString(0), ((JsonArray) header).getString(1));
                }
                headers.add(((JsonArray) header).getString(0), ((JsonArray) header).getString(1));
            });
            return headers;
        }

        // evaluate dynamicHeaders
        if(requestContainer.getHeaderFunction() != HeaderFunctions.DO_NOTHING){
            headers.addAll(originalRequestHeaders);
            final HeaderFunctions.EvalScope evalScope = requestContainer.getHeaderFunction().apply(headers);
            if (evalScope.getErrorMessage() != null) {
                LOG.warn("problem applying header manipulator chain {} in delegate {}", evalScope.getErrorMessage(), getName());
            }
        }

        return headers;
    }

    /**
     * Extract the json payload of the original request when a delegate request definition with a transformation spec
     * is defined. If no transformation is found, the payload will not be parsed and <code>null</code> will be returned.
     * @param request the request to get the payload from
     * @return returns a parsed json payload as string or null
     */
    private Future<String> extractDelegateExecutionRequestJsonPayload(HttpServerRequest request){
        Future<String> future = Future.future();
        if(delegateContainsJoltSpecRequest) {
            request.bodyHandler(bodyHandler -> {
                try {
                    future.complete(bodyHandler.toJsonObject().encode());
                } catch (Exception ex) {
                    future.fail(ex);
                }
            });
        } else {
            future.complete(null);
        }
        return future;
    }

    private Future<Buffer> generatePayload(String delegateExecutionRequestJsonPayload, MultiMap headers, DelegateRequest requestContainer, final Matcher matcher) {
        Future<Buffer> future = Future.future();

        if (requestContainer.getJoltSpec() != null) {
            try {
                if(delegateExecutionRequestJsonPayload != null){

                    String transformInput = TransformPayloadInputBuilder.build(requestContainer.getJoltSpec(),
                            delegateExecutionRequestJsonPayload, headers, matcher);
                    LOG.debug("Jolt transformation input: {}", transformInput);
                    JoltTransformer.transform(transformInput, requestContainer.getJoltSpec()).setHandler(transformed -> {
                        if(transformed.failed()){
                            future.fail(transformed.cause());
                        } else {
                            JsonObject transformedJsonObject = transformed.result();
                            try {
                                String transformedOutput = transformedJsonObject.encode();
                                LOG.debug("Jolt transformation output: {}", transformedOutput);
                                future.complete(Buffer.buffer(transformedOutput));
                            } catch (Exception ex){
                                future.fail(ex);
                            }
                        }
                    });
                } else {
                    future.fail("nothing to transform");
                }
            } catch (Exception ex){
                future.fail(ex);
            }
        } else {
            // matcher to replace wildcards with matching groups
            final JsonObject requestObject = requestContainer.getRequest();
            // get the string represantion of the payload object
            String payloadStr;
            try {
                payloadStr = requestObject.getString(PAYLOAD);
            } catch (ClassCastException e) {
                payloadStr = requestObject.getJsonObject(PAYLOAD).encode();
            }

            // replacement of matching groups
            if (payloadStr != null) {
                payloadStr = matcher.replaceAll(payloadStr);
                future.complete(Buffer.buffer(payloadStr));
            } else {
                future.complete(null);
            }
        }
        return future;
    }

    /**
     * Creates a new DoneHandler for the given request.
     *
     * @param request the original request
     * @return a doneHandler
     */
    private Handler<HttpClientResponse> installDoneHandler(final HttpServerRequest request, final String delegateExecutionRequestJsonPayload) {
        return new Handler<>() {
            private AtomicInteger currentIndex = new AtomicInteger(0);

            @Override
            public void handle(HttpClientResponse response) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Done handler - handle");
                }

                // request was fine
                if ((response.statusCode() / 100) == STATUS_CODE_2XX) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Done handler - OK");
                    }

                    // is there another request?
                    if (currentIndex.incrementAndGet() < requests.size()) {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Done handler - calling next {}", currentIndex.get());
                        }

                        final DelegateRequest delegateRequest = requests.get(currentIndex.get());
                        createRequest(request, delegateExecutionRequestJsonPayload, delegateRequest, this);
                    }
                    // if not, send corresponding respond
                    else {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Done handler - not 2XX, create response [{}]", response.statusCode());
                        }
                        createResponse(request, response);
                    }
                }
                // request failed
                else {
                    if (LOG.isTraceEnabled()) {
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
     * @param request  original request
     * @param response a response
     */
    private void createResponse(final HttpServerRequest request, final HttpClientResponse response) {
        HttpServerRequestUtil.prepareResponse(request, response);

        response.handler(data -> request.response().write(data));
        response.endHandler(v -> request.response().end());
    }

    private boolean doesDelegateContainJoltSpecRequest(){
        if(this.requests == null || this.requests.isEmpty()){
            return false;
        }
        for (DelegateRequest request : requests) {
            if(request.getJoltSpec() != null){
                return true;
            }
        }
        return false;
    }
}
