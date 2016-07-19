package org.swisspush.gateleen.expansion;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;

import java.util.List;
import java.util.Set;

/**
 * Provides an appropriate handler for
 * handling the recursive GET feature.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public final class RecursiveHandlerFactory {

    /**
     * private constructor to keep
     * sonar happy.
     */
    private RecursiveHandlerFactory() {
    }

    /**
     * An enumeration with the valid RecursionHandlers
     * available for this factory.
     * 
     * @author https://github.com/ljucam [Mario Ljuca]
     */
    public enum RecursiveHandlerTypes {
        EXPANSION, ZIP, STORE
    }

    /**
     * Creates a handler for the desired
     * functionality of the recursive GET
     * feature.
     * The desired handler can be selected
     * by the enumeration <code>RecursionHandlerTypes</code>.
     * If the desired handler is not yet
     * implemented, <code>null</code> is returned instead.
     * 
     * @param type type
     * @param subResourceNames subResourceNames
     * @param collectionName collectionName
     * @param collectioneTag collectioneTag
     * @param parentHandler parentHandler
     * @return the wished handler
     */
    public static DeltaHandler<ResourceNode> createHandler(RecursiveHandlerTypes type, List<String> subResourceNames, String collectionName, String collectioneTag, DeltaHandler<ResourceNode> parentHandler) {
        switch (type) {
        case EXPANSION:
            return new RecursiveExpansionHandler(subResourceNames, collectionName, collectioneTag, parentHandler);
        case ZIP:
        case STORE:
            return new RecursiveZipHandler(subResourceNames, collectionName, parentHandler);
        default:
            return null;
        }
    }

    /**
     * Creates a root handler for the desired
     * functionality of the recursive GET
     * feature.
     * The desired handler can be selected
     * by the enumeration <code>RecursionHandlerTypes</code>.
     * If the desired handler is not yet
     * implemented, <code>null</code> is returned instead.
     * 
     * @param type type
     * @param request request
     * @param serverRoot serverRoot
     * @param data data
     * @param finalOriginalParams finalOriginalParams
     * @return Handler
     */
    public static DeltaHandler<ResourceNode> createRootHandler(RecursiveHandlerTypes type, HttpServerRequest request, String serverRoot, Buffer data, Set<String> finalOriginalParams) {
        switch (type) {
        case EXPANSION:
            return new RecursiveExpansionRootHandler(request, data, finalOriginalParams);
        case ZIP:
        case STORE:
            return new RecursiveZipRootHandler(request, serverRoot, data, finalOriginalParams, type);
        default:
            return null;
        }
    }
}
