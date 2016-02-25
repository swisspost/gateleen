package org.swisspush.gateleen.core.resource;

import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Allows to copy one single resource to another destination.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class CopyResourceHandler {
    private Logger log = LoggerFactory.getLogger(CopyResourceHandler.class);
    private static final String SLASH = "/";
    private static final String POST_METHOD = "POST";
    private static final String GET_METHOD = "GET";
    private static final String PUT_METHOD = "PUT";
    private static final int DEFAULT_TIMEOUT = 120000;

    private String copyPath;
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
        // check if request is copy task and POST
        if (request.uri().equalsIgnoreCase(copyPath) && HttpMethod.POST == request.method()) {
            log.debug("handle -> {}", request.uri());

            // process task
            request.bodyHandler(buffer -> {
                CopyTask task = createCopyTask(request, buffer);
                if (validTask(request, task)) {
                    performGETRequest(request, task);
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
     * @param task - the copy task
     * @return true if the task is valid otherwise false
     */
    protected boolean validTask(HttpServerRequest request, CopyTask task) {
        // source or destination is collection?
        if (task.getSourceUri().endsWith(SLASH) || task.getDestinationUri().endsWith(SLASH)) {
            log.debug("invalid copy task, collections are not allowed!");
            request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
            request.response().end();

            return false;
        }

        return true;
    }

    /**
     * Performs the intial GET request to the source.
     * 
     * @param request - the original request
     * @param task - the task which has to be performed
     */
    protected void performGETRequest(final HttpServerRequest request, final CopyTask task) {

        // perform Initial GET request
        HttpClientRequest selfRequest = selfClient.get(task.getSourceUri(), response -> {
            // POST response is OK
            if (response.statusCode() == StatusCode.OK.getStatusCode()) {
                performPUTRequest(request, response, task);
            } else {
                createResponse(request, response, task);
            }
        });

        // setting headers
        selfRequest.headers().setAll(task.getHeaders());

        // avoids blocking other requests
        selfRequest.setTimeout(DEFAULT_TIMEOUT);

        // fire
        selfRequest.end();
    }

    /**
     * Performs the PUT request to the target.
     * 
     * @param request - the original request
     * @param getResponse - the GET response
     * @param task - the task
     */
    protected void performPUTRequest(final HttpServerRequest request, final HttpClientResponse getResponse, final CopyTask task) {
        getResponse.bodyHandler(new Handler<Buffer>() {

            @Override
            public void handle(final Buffer data) {
                // GET response is OK
                if (getResponse.statusCode() == StatusCode.OK.getStatusCode()) {

                    HttpClientRequest selfRequest = selfClient.put(task.getDestinationUri(), response -> {
                        createResponse(request, response, task);
                    });

                    // setting headers
                    selfRequest.headers().addAll(task.getHeaders());
                    selfRequest.headers().addAll(task.getStaticHeaders());

                    // writing data
                    selfRequest.write(data);

                    // avoids blocking other requests
                    selfRequest.setTimeout(DEFAULT_TIMEOUT);

                    // fire
                    selfRequest.end();

                } else {
                    createResponse(request, getResponse, task);
                }
            }
        });
    }

    /**
     * Create a response of the original request.
     * 
     * @param request - the original request
     * @param response - the resulting response
     */
    private void createResponse(final HttpServerRequest request, final HttpClientResponse response, final CopyTask task) {
        if (response.statusCode() == StatusCode.OK.getStatusCode()) {
            log.debug("copy resource task successfully executed: {} -> {}", task.getSourceUri(), task.getDestinationUri());
        } else {
            log.debug("copy resource task failed: {} -> {}", task.getSourceUri(), task.getDestinationUri());
        }

        request.response().setStatusCode(response.statusCode());
        request.response().setStatusMessage(response.statusMessage());
        response.bodyHandler(new Handler<Buffer>() {

            @Override
            public void handle(Buffer buffer) {
                request.response().end(buffer);
            }
        });
    }

    /**
     * Creates a new copy task based on the request body and the given headers.
     * 
     * @param request - the original request
     * @param buffer - the buffer of the request
     * @return a new CopyTask
     */
    private CopyTask createCopyTask(final HttpServerRequest request, final Buffer buffer) {
        JsonObject task = new JsonObject(buffer.toString());

        JsonObject staticHeadersJson = task.getJsonObject("staticHeaders");
        MultiMap staticHeaders = new CaseInsensitiveHeaders();
        if (staticHeadersJson != null && staticHeadersJson.size() > 0) {
            for (Map.Entry<String, Object> entry : staticHeadersJson.getMap().entrySet()) {
                staticHeaders.add(entry.getKey(), entry.getValue().toString());
            }
        }

        return new CopyTask(task.getString("source"), task.getString("destination"), request.headers(), staticHeaders);
    }
}
