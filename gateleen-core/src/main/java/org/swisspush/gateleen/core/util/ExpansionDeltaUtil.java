package org.swisspush.gateleen.core.util;

import com.google.common.base.Joiner;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * <p>
 * Utility class providing methods used in ExpansionHandler and DeltaHandler.
 * </p>
 * 
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public final class ExpansionDeltaUtil {

    private static final String SLASH = "/";

    private static Logger log = LoggerFactory.getLogger(ExpansionDeltaUtil.class);

    private ExpansionDeltaUtil() {
    }

    /**
     * Converts the given map to a delimited string based on the given delimiter.
     * 
     * <pre>
     * <b>Example</b>
     * Input Map: {k1=v1, k2=v2, k3=v3} Input delim: 
     * Result: k1=v1&amp;amp;k2=v2&amp;amp;k3=v3
     * </pre>
     * 
     * @param map The map to convert.
     * @param delim The delimiter to use between key-value pairs.
     * @return The delimited string.
     */
    public static String mapToDelimetedString(MultiMap map, String delim) {
        return Joiner.on(delim).withKeyValueSeparator("=").join(map);
    }

    /**
     * Remove the stringToRemove from source when existing
     * 
     * @param source the source to remove the stringToRemove from
     * @param stringToRemove the token to remove from source
     * @return String
     */
    public static String removeFromEndOfString(String source, String stringToRemove) {
        if (stringToRemove == null) {
            return source;
        }
        String result = source;
        if (source != null && source.endsWith(stringToRemove)) {
            result = source.substring(0, source.length() - 1);
        }
        return result;
    }

    /**
     * Extracts the collection name from the given path. The collection name is known to be the last segment of the path
     * 
     * @param path path
     * @return String
     */
    public static String extractCollectionFromPath(String path) {
        String extractedCollectionName = null;
        String pathModified = removeFromEndOfString(path, SLASH);
        String[] pathSegments = pathModified.split("/");
        if (pathSegments.length > 0) {
            extractedCollectionName = pathSegments[pathSegments.length - 1];
        }
        return extractedCollectionName;
    }

    /**
     * Extracts the collection resource names from the given {@link JsonArray}
     * 
     * @param collectionArray collectionArray
     * @return the list of collection names
     * @throws ResourceCollectionException ResourceCollectionException
     */
    public static List<String> extractCollectionResourceNames(JsonArray collectionArray) throws ResourceCollectionException {
        List<String> collectionResourceNames = new ArrayList<String>();
        for (Object colEntry : collectionArray) {
            if (!(colEntry instanceof String)) {
                throw new ResourceCollectionException("the backend doesn't seem to support delta-handling on this resource", StatusCode.BAD_REQUEST);
            }
            collectionResourceNames.add((String) colEntry);
        }
        return collectionResourceNames;
    }

    /**
     * Constructs a Request based on the given path and the given params without the paramsToRemove
     * 
     * @param path the original path to modify
     * @param params the params of the original request
     * @param paramsToRemove the params which should be removed in the new request
     * @param subResource additional segment for the new request
     * @param slashHandling when set to true, the new request will have a / at the end
     * @return String
     */
    public static String constructRequestUri(String path, MultiMap params, List<String> paramsToRemove, String subResource, SlashHandling slashHandling) {
        String result = path;
        if (paramsToRemove != null) {
            for (String paramToRemove : paramsToRemove) {
                params.remove(paramToRemove);
            }
        }

        boolean pathEndsWithSlash = result.endsWith(SLASH);

        if (subResource != null) {
            if (pathEndsWithSlash) {
                result = result + subResource;
            } else {
                result = result + SLASH + subResource;
            }
        }

        if (slashHandling.equals(SlashHandling.END_WITH_SLASH)) {
            if (!pathEndsWithSlash) {
                result = result + SLASH;
            }
        } else if (slashHandling.equals(SlashHandling.END_WITHOUT_SLASH)) {
            result = removeFromEndOfString(result, SLASH);
        }
        if (!params.isEmpty()) {
            result = result + "?" + ExpansionDeltaUtil.mapToDelimetedString(params, "&");
        }
        return result;
    }

    public enum SlashHandling {
        END_WITH_SLASH, END_WITHOUT_SLASH, KEEP
    }

    /**
     * Utility method to create a Exception for a {@link HttpServerRequest}
     * 
     * @param request the request
     * @param uri an uri
     * @param caller the caller
     * @return Handler
     */
    public static Handler<Throwable> createRequestExceptionHandler(final HttpServerRequest request, final String uri, final Class<?> caller) {
        return exception -> {
            if (log.isTraceEnabled()) {
                log.trace("end response with content");
            }
            if (exception instanceof TimeoutException) {
                error("Timeout", request, uri, caller);
                request.response().setStatusCode(StatusCode.TIMEOUT.getStatusCode());
                request.response().setStatusMessage(StatusCode.TIMEOUT.getStatusMessage());
                try {
                    request.response().end(request.response().getStatusMessage());
                } catch (IllegalStateException e) {
                    // ignore because maybe already closed
                }
            } else {
                error(exception.getMessage(), request, uri, caller);
                request.response().setStatusCode(StatusCode.SERVICE_UNAVAILABLE.getStatusCode());
                request.response().setStatusMessage(StatusCode.SERVICE_UNAVAILABLE.getStatusMessage());
                try {
                    request.response().end(request.response().getStatusMessage());
                } catch (IllegalStateException e) {
                    // ignore because maybe already closed
                }
            }
        };
    }

    /**
     * Utility method to create a {@link Exception} for a {@link HttpServerRequest}
     * 
     * @param request the request
     * @param uri an uri
     * @param caller the caller
     * @return Handler
     */
    public static Handler<Throwable> createResponseExceptionHandler(final HttpServerRequest request, final String uri, final Class<?> caller) {
        return exception -> {
            error("Problem with backend: " + exception.getMessage(), request, uri, caller);
            request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            request.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
            try {
                request.response().end(request.response().getStatusMessage());
            } catch (IllegalStateException e) {
                // ignore because maybe already closed
            }
        };
    }

    /**
     * Verifies the result of the collection request. Throws a {@link ResourceCollectionException} when no collection or more than one collection was found Returns a
     * {@link CollectionResourceContainer} containing the name of the collection and a list of the resourceNames.
     * 
     * @param request the request
     * @param data the data
     * @param originalParams originalParams
     * @return CollectionResourceContainer
     * @throws ResourceCollectionException ResourceCollectionException
     */
    public static CollectionResourceContainer verifyCollectionResponse(HttpServerRequest request, Buffer data, Set<String> originalParams) throws ResourceCollectionException {
        checkResponse(request);
        String targetPath = request.path();
        return verifyCollectionResponse(targetPath, data, originalParams);
    }

    /**
     * Simple container holding a collectionName and a list of resourceNames
     * 
     * @author https://github.com/mcweba [Marc-Andre Weber]
     */
    public static class CollectionResourceContainer {
        private final String collectionName;
        private final List<String> resourceNames;

        public CollectionResourceContainer(String collectionName, List<String> resourceNames) {
            this.collectionName = collectionName;
            this.resourceNames = resourceNames;
        }

        public String getCollectionName() {
            return collectionName;
        }

        public List<String> getResourceNames() {
            return resourceNames;
        }
    }

    private static void error(String message, HttpServerRequest request, String uri, Class<?> caller) {
        RequestLoggerFactory.getLogger(caller, request).error(uri + " " + message);
    }

    private static void checkResponse(HttpServerRequest request) throws ResourceCollectionException {
        StatusCode statusCode = StatusCode.fromCode(request.response().getStatusCode());
        if (statusCode != null && statusCode != StatusCode.OK) {
            throw new ResourceCollectionException(request.response().getStatusMessage(), statusCode);
        }
    }

    /**
     * Verifies the result of the collection request. Throws a {@link ResourceCollectionException} when no collection
     * or more than one collection was found Returns a {@link CollectionResourceContainer} containing the name of the
     * collection and a list of the resourceNames.
     * 
     * @param targetPath target path
     * @param data the data
     * @param originalParams originalParams
     * @return CollectionResourceContainer
     * @throws ResourceCollectionException ResourceCollectionException
     */
    public static CollectionResourceContainer verifyCollectionResponse(String targetPath, Buffer data, Set<String> originalParams) throws ResourceCollectionException {
        String collectionName = ExpansionDeltaUtil.extractCollectionFromPath(targetPath);
        if (collectionName == null) {
            throw new ResourceCollectionException("No collection name found in path " + targetPath, StatusCode.BAD_REQUEST);
        }

        if (data.length() == 0) {
            String params = "''";
            if (originalParams != null) {
                params = originalParams.toString();
            }
            throw new ResourceCollectionException("Request did not return data. Invalid usage of params " + params + " ?", StatusCode.BAD_REQUEST);
        }

        // Parse payload
        final String dataAsString = data.toString(UTF_8);
        final JsonObject obj;
        try {
            obj = new JsonObject(dataAsString);
        } catch (DecodeException e) {
            final int MAX_CHARS_TO_PRINT = 4096;
            final String msgHead = "Failed to decode data as JSON.";
            final String msgTail = "First " + MAX_CHARS_TO_PRINT + " characters were:\n" +
                    // Append first n characters from malformed data
                    dataAsString.substring(0, Math.min(MAX_CHARS_TO_PRINT, dataAsString.length())) + "\n";
            // Log as INFO only because this situation is not our fault and we can continue
            // our service with no problems.
            log.info(msgHead, e);
            throw new ResourceCollectionException(msgHead + "\n\n" + msgTail, StatusCode.BAD_GATEWAY, e);
        }

        JsonArray collectionEntries = obj.getJsonArray(collectionName);
        if (collectionEntries == null) {
            throw new ResourceCollectionException("Collection with name '" + collectionName + "' not found in result of request " + targetPath, StatusCode.BAD_REQUEST);
        }
        return new CollectionResourceContainer(collectionName, extractCollectionResourceNames(collectionEntries));
    }
}
