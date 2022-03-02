package org.swisspush.gateleen.core.resource;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HeaderFunction;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.HttpHeaderUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StatusCodeTranslator;

/**
 * Allows to copy one single resource to another destination.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class CopyResourceHandler {
    private static final Logger log = LoggerFactory.getLogger(CopyResourceHandler.class);

    private static final String SLASH = "/";
    private static final int DEFAULT_TIMEOUT = 120000;

    private final String copyPath;
    private final HttpClient selfClient;

    public CopyResourceHandler(HttpClient selfClient, String copyPath) {
        this.selfClient = selfClient;
        this.copyPath = copyPath;
    }

    /**
     * Handles the copy task.
     *
     * @param request - the request
     * @return true if the request was handled, false otherwise
     */
    public boolean handle(final HttpServerRequest request) {
        Logger log = RequestLoggerFactory.getLogger(CopyResourceHandler.class, request);
        // check if request is copy task and POST
        if (request.uri().equalsIgnoreCase(copyPath) && HttpMethod.POST == request.method()) {
            log.debug("handle -> {}", request.uri());

            // process task
            request.bodyHandler(buffer -> {
                CopyTask task = createCopyTask(request, buffer);
                if (task != null && validTask(request, task)) {
                    performGETRequest(request, task);
                } else {
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                    request.response().end();
                }
            });

            return true;
        } else {
            return false;
        }
    }

    /**
     * Checks if the copy task is valid (simple resource, not a collection).
     *
     * @param request - the original request
     * @param task    - the copy task
     * @return true if the task is valid otherwise false
     */
    protected boolean validTask(HttpServerRequest request, CopyTask task) {
        // source or destination is collection?
        if (task.getSourceUri().endsWith(SLASH) || task.getDestinationUri().endsWith(SLASH)) {
            Logger log = RequestLoggerFactory.getLogger(CopyResourceHandler.class, request);
            log.debug("invalid copy task, collections are not allowed!");
            return false;
        }

        return true;
    }


    /**
     * Performs the initial GET request to the source.
     *
     * @param request - the original request
     * @param task    - the task which has to be performed
     */
    protected void performGETRequest(final HttpServerRequest request, final CopyTask task) {
        // perform Initial GET request

        selfClient.request(HttpMethod.GET, task.getSourceUri()).onComplete(event -> {
            if (event.failed()) {
                log.warn("Failed request to {}: {}", request.uri(), event.cause());
                return;
            }
            HttpClientRequest selfRequest = event.result();
            // setting headers
            selfRequest.headers().setAll(task.getHeaders());

            // avoids blocking other requests
            selfRequest.setTimeout(DEFAULT_TIMEOUT);

            // add exception handler
            selfRequest.exceptionHandler(exception -> log.warn("CopyResourceHandler: GET request failed: " + request.uri() + ": " + exception.getMessage()));

            // fire
            selfRequest.send(asyncResult -> {
                HttpClientResponse response = asyncResult.result();
                // POST response is OK
                if (response.statusCode() == StatusCode.OK.getStatusCode()) {
                    performPUTRequest(request, response, task);
                } else {
                    createResponse(request, response, task);
                }
            });
        });
    }

    /**
     * Performs the PUT request to the target.
     *
     * @param request     - the original request
     * @param getResponse - the GET response
     * @param task        - the task
     */
    protected void performPUTRequest(final HttpServerRequest request, final HttpClientResponse getResponse, final CopyTask task) {
        getResponse.bodyHandler(data -> {
            // GET response is OK
            if (getResponse.statusCode() == StatusCode.OK.getStatusCode()) {

                selfClient.request(HttpMethod.PUT, task.getDestinationUri()).onComplete(event -> {
                    if (event.failed()) {
                        log.warn("Failed request to {}: {}", request.uri(), event.cause());
                        return;
                    }
                    HttpClientRequest selfRequest = event.result();

                    // setting headers
                    selfRequest.headers().addAll(task.getHeaders());

                    // writing data
                    selfRequest.write(data);

                    // avoids blocking other requests
                    selfRequest.setTimeout(DEFAULT_TIMEOUT);

                    // fire
                    selfRequest.send(asyncResult -> {
                        HttpClientResponse response = asyncResult.result();
                        createResponse(request, response, task);
                    });
                });
            } else {
                createResponse(request, getResponse, task);
            }
        });
    }

    /**
     * Create a response of the original request.
     *
     * @param request  - the original request
     * @param response - the resulting response
     */
    private void createResponse(final HttpServerRequest request, final HttpClientResponse response, final CopyTask task) {
        Logger log = RequestLoggerFactory.getLogger(CopyResourceHandler.class, request);
        if (response.statusCode() == StatusCode.OK.getStatusCode()) {
            log.debug("copy resource task successfully executed: {} -> {}", task.getSourceUri(), task.getDestinationUri());
        } else {
            log.debug("copy resource task failed: {} -> {}", task.getSourceUri(), task.getDestinationUri());
        }

        request.response().setStatusCode(StatusCodeTranslator.translateStatusCode(response.statusCode(), request.headers()));
        request.response().setStatusMessage(response.statusMessage());
        response.bodyHandler(buffer -> {
            request.response().putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(buffer.length()));
            request.response().end(buffer);
        });
    }

    /**
     * Creates a new copy task based on the request body and the given headers.
     *
     * @param request - the original request
     * @param buffer  - the buffer of the request
     * @return a new CopyTask
     */
    CopyTask createCopyTask(final HttpServerRequest request, final Buffer buffer) {
        JsonObject task = new JsonObject(buffer.toString());
        HeaderFunction headerFunction = HeaderFunctions.DO_NOTHING;

        JsonObject staticHeaders = task.getJsonObject("staticHeaders");
        if (staticHeaders != null) {
            headerFunction = HeaderFunctions.parseStaticHeadersFromJson(staticHeaders);
        } else {
            final JsonArray dynamicHeaders = task.getJsonArray("headers");
            if (dynamicHeaders != null) {
                headerFunction = HeaderFunctions.parseFromJson(dynamicHeaders);
            }
        }

        MultiMap headers = StatusCodeTranslator.getTranslateFreeHeaders(request.headers());

        final HeaderFunctions.EvalScope evalScope = headerFunction.apply(headers);
        if (evalScope.getErrorMessage() != null) {
            log.warn("Problem invoking Header functions: {}", evalScope.getErrorMessage());
            return null;
        }

        HttpHeaderUtil.removeNonForwardHeaders(headers);
        headers.remove("content-length");

        // create copy task
        return new CopyTask(task.getString("source"), task.getString("destination"), headers);
    }


}
