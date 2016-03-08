package org.swisspush.gateleen.expansion;

import org.swisspush.gateleen.core.util.*;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.CollectionResourceContainer;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Set;

/**
 * Creates a root handler for the recursive HTTP GET.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class RecursiveExpansionRootHandler extends RecursiveRootHandlerBase {
    private static final String ETAG_HEADER = "Etag";
    private static final String IF_NONE_MATCH_HEADER = "if-none-match";

    private final HttpServerRequest req;
    private final Buffer data;
    private final Set<String> finalOriginalParams;

    /**
     * Creates an instance of the root handler for the
     * recursive HTTP GET.
     * 
     * @param req req
     * @param data data
     * @param finalOriginalParams finalOriginalParams
     */
    public RecursiveExpansionRootHandler(final HttpServerRequest req, Buffer data, Set<String> finalOriginalParams) {
        this.req = req;
        this.data = data;
        this.finalOriginalParams = finalOriginalParams;
    }

    @Override
    public void handle(ResourceNode node) {
        if (log.isTraceEnabled()) {
            log.trace("parent handler called > " + (node != null ? node.getNodeName() : "not found"));
        }

        /*
         * each recursive has one json object (container).
         * This container contains one json object, with the
         * name of the 'expanded' resource, aka the parent
         * or root.
         * {
         * "parent" :{ ... }
         * }
         */

        try {
            CollectionResourceContainer collection = ExpansionDeltaUtil.verifyCollectionResponse(req, data, finalOriginalParams);

            // pure data
            if (node.getObject() instanceof Buffer) {
                try {
                    node.setObject(new JsonObject(((Buffer) node.getObject()).toString("UTF-8")));
                } catch (Exception e) {
                    log.error("Error in result of sub resource '" + node.getNodeName() + "' Message: " + e.getMessage());
                    node.setObject(new ResourceCollectionException(e.getMessage()));
                }
            }

            checkIfError(node);

            JsonObject container = new JsonObject();
            if (node.getObject() instanceof JsonObject) {
                container.put(collection.getCollectionName(), (JsonObject) node.getObject());
            } else {
                container.put(collection.getCollectionName(), (JsonArray) node.getObject());
            }

            buildAndSendResult(req, container, node.geteTag());
        } catch (ResourceCollectionException exception) {
            handleResponseError(req, exception);
        } catch (Exception exception) {
            ResponseStatusCodeLogUtil.debug(req, StatusCode.INTERNAL_SERVER_ERROR, RecursiveExpansionRootHandler.class);
            req.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            req.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
            req.response().end(exception.getMessage());
        }
    }

    /**
     * Creates the final result and sends it
     * as a response to the client.
     * 
     * @param req
     * @param responseObject
     * @param collectedeTags
     */
    private void buildAndSendResult(HttpServerRequest req, JsonObject responseObject, String collectedeTags) {
        String etagFromResources = HashCodeGenerator.createSHA256HashCode(collectedeTags);

        if (finalOriginalParams.contains("delta")) {
            req.response().headers().set("x-delta", "" + xDeltaResponseNumber);
        }

        JsonObject responseContent = makeCachedResponse(req, etagFromResources, responseObject);

        if (responseContent == null) {
            if (log.isTraceEnabled()) {
                log.trace("end response without content");
            }
            req.response().end();
        } else {
            if (log.isTraceEnabled()) {
                log.trace("end response with content");
            }
            req.response().end(responseObject.toString());
        }
    }

    /**
     * Creates a cached response. <br />
     * If no cached data is found, the responseObject is
     * returned. <br />
     * If cached data is found, <code>null</code> is returned instead.
     * 
     * @param req - original request
     * @param etagFromResources - new etags
     * @param responseObject - the response object
     * @return
     */
    private JsonObject makeCachedResponse(HttpServerRequest req, String etagFromResources, JsonObject responseObject) {
        JsonObject result = responseObject;

        if (log.isTraceEnabled()) {
            log.trace("Header from request:  " + req.headers().get(IF_NONE_MATCH_HEADER));
            log.trace("Header from response: " + etagFromResources);
        }

        if (etagFromResources != null) {
            req.response().headers().add(ETAG_HEADER, etagFromResources);
            String ifNoneMatch = req.headers().get(IF_NONE_MATCH_HEADER);
            if (etagFromResources.equals(ifNoneMatch)) {
                ResponseStatusCodeLogUtil.debug(req, StatusCode.NOT_MODIFIED, RecursiveExpansionRootHandler.class);
                req.response().setStatusCode(StatusCode.NOT_MODIFIED.getStatusCode());
                req.response().setStatusMessage(StatusCode.NOT_MODIFIED.getStatusMessage());
                req.response().headers().add("Content-Length", "0");
                req.response().setChunked(false);
                result = null;
            } else {
                ResponseStatusCodeLogUtil.debug(req, StatusCode.OK, RecursiveExpansionRootHandler.class);
            }
        } else {
            ResponseStatusCodeLogUtil.debug(req, StatusCode.OK, RecursiveExpansionRootHandler.class);
        }
        return result;
    }
}
