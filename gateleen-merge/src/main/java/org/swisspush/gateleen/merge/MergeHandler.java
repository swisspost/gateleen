package org.swisspush.gateleen.merge;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.CollectionContentComparator;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil;
import org.swisspush.gateleen.core.util.HttpServerRequestUtil;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Allows to perform a request over more than one route.  <br>
 * The MergeHandler has to be addressed by the header <code>x-merge-collections</code>. <br>
 * The following example shows how to use the MergeHandler. <br>
 * <pre>
 * "/gateleen/data/(.*)" : {
 * "path": "data/$1",
 * "staticHeaders": {
 * "x-merge-collections": "/gateleen/masterdata/parent/"
 * }
 * }
 * </pre>
 *
 * <b>Note:</b> <br>
 * The following parameters do not work with the MergeHandler: <br>
 * <ul>
 *     <li>x-delta</li>
 *     <li></li>
 * </ul>
 *
 * @author https://github.com/ljucam [Mario Aerni]
 */
public class MergeHandler {
    private static Logger log = LoggerFactory.getLogger(MergeHandler.class);

    public static final String MISSMATCH_ERROR = "Resources as well as collections with the given name were found.";
    private static final String MERGE_HEADER = "x-merge-collections";
    private static final String SELF_REQUEST_HEADER = "x-self-request";
    private static final int NO_PARAMETER_FOUND = -1;
    private static final int TIMEOUT = 120000;
    private static final String SLASH = "/";

    private final HttpClient httpClient;
    private final Comparator<String> collectionContentComparator;

    /**
     * Creates a new instance of the MergeHandler
     *
     * @param httpClient the self client
     */
    public MergeHandler(HttpClient httpClient) {
        this.httpClient = httpClient;
        collectionContentComparator = new CollectionContentComparator();
    }

    /**
     * Checks if the MergeHandler is responsible for this request.
     * If so it processes the request and returns true, otherwise
     * it returns false.
     *
     * @param request original request
     * @return true if processed, false otherwise
     */
    public boolean handle(final HttpServerRequest request) {
        final String mergeCollection = request.getHeader(MERGE_HEADER);

        if (mergeCollection != null && request.method().equals(HttpMethod.GET)) {

            // perform a get request on the parent, to get all collections of the routes
            httpClient.request(HttpMethod.GET, mergeCollection).onComplete(asyncReqResult -> {
                if (asyncReqResult.failed()) {
                    log.warn("Failed request to {}: {}", request.uri(), asyncReqResult.cause());
                    return;
                }
                HttpClientRequest cReq = asyncReqResult.result();


                cReq.setTimeout(TIMEOUT);
                cReq.headers().set("Accept", "application/json");
                cReq.headers().set(SELF_REQUEST_HEADER, "true");
                cReq.setChunked(true);
                cReq.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(request, mergeCollection, MergeHandler.class));
                cReq.send(asyncResult -> {
                    HttpClientResponse cRes = asyncResult.result();
                    // everything is ok
                    if (cRes.statusCode() == StatusCode.OK.getStatusCode()) {
                        final String collectionName = getCollectionName(mergeCollection);
                        final String targetUrlPart = getTargetUrlPart(request.path());

                        if (log.isTraceEnabled()) {
                            log.trace("handle > (mergeCollection) {}, (collectionName) {}, (targetUrlPart) {}", mergeCollection, collectionName, targetUrlPart);
                        }

                        // process respons
                        cRes.handler(data -> {
                            JsonObject dataObject = new JsonObject(data.toString());

                            if (log.isTraceEnabled()) {
                                log.trace(" >> body is \"{}\"", dataObject.toString());
                            }

                            // we get an array back
                            if (dataObject.getValue(collectionName) instanceof JsonArray) {
                                List<String> collections = getCollections(dataObject.getJsonArray(collectionName));

                                final Handler<MergeData> mergeCollectionHandler = installMergeCollectionHandler(request, collections.size(), targetUrlPart);

                                for (final String collection : collections) {
                            /*
                                In order to perform the right request (direct request for a resource,
                                merge request for a collection), we are forced to first perform a
                                request to the underlying collection. This seems to be the only way to
                                distinguish weather we hare requesting a collection or a resource.
                                We cannot ask redis (storage), because we may perform this request
                                against a service behind a dynamic route.
                                This way we always get two requests (one for the underlying collection and one
                                for the wished original request).
                                We may however improve the performance by using the following algorithm.

                                First request the underlying collection (for all routes) and mark the ones,
                                which are not available (404 - NOT FOUND). This one’s doesn't have to be
                                requested again.

                                If an error occured (not 404), create an error response.
                             */
                                    if (log.isTraceEnabled()) {
                                        log.trace("requestCollection {}", collection);
                                    }
                                    requestCollection(request, mergeCollection, collection, request.path(), mergeCollectionHandler);
                                }
                            } else {
                                // write the data back
                                request.response().end(dataObject.toBuffer());
                            }
                        });
                    }
                    // something is odd
                    else {
                        request.response().setChunked(true);
                        cRes.handler(data -> request.response().write(data));
                        cRes.endHandler(v -> request.response().end());
                    }
                });
            });
            return true;
        }

        // the request was not processed by the MergeHandler
        return false;
    }

    /**
     * Performs a request to the parent resource of the resulting
     * request. This way it is possible to determin if we have a
     * resource or a collection request.
     *
     * @param request                the original request
     * @param mergeCollection        the path value from the header
     * @param collection             the requested sub resource with trailing slash
     * @param path                   the path component from the rule
     * @param mergeCollectionHandler the merge handler
     */
    private void requestCollection(final HttpServerRequest request,
                                   final String mergeCollection,
                                   final String collection,
                                   final String path,
                                   final Handler<MergeData> mergeCollectionHandler) {
        /*
         mergeCollection                +                collection                +                path
         /gateleen/masterdata/parent/                    collection1/                               data/whatever
         /gateleen/masterdata/parent/collection1/data/whatever

         First request will be
         parentCollection of /gateleen/masterdata/parent/collection1/data/whatever
         =>  /gateleen/masterdata/parent/collection1/data/
         Result can be: (will result in merge request)
         {
            "data" : [
                "whatever/"
            ]
         }

         Or: (will result in direct request)
         {
            "data" : [
                "whatever"
            ]
         }

          */
        final String requestUrl = mergeCollection + collection +
                (path.startsWith(SLASH) ? path.substring(path.indexOf(SLASH) + 1, path.length()) : path);
        final String parentUrl = prepareParentCollection(requestUrl);
        final String targetUrlPart = getTargetUrlPart(requestUrl);

        if (log.isTraceEnabled()) {
            log.trace("requestCollection > (requestUrl)" + requestUrl + " (parentUrl) " + parentUrl + " (targetUrlPart) " + targetUrlPart);
        }

        httpClient.request(HttpMethod.GET, parentUrl).onComplete(asyncReqResult -> {
            if (asyncReqResult.failed()) {
                log.warn("Failed request to {}: {}", request.uri(), asyncReqResult.cause());
                return;
            }
            HttpClientRequest collectionRequest = asyncReqResult.result();


            collectionRequest.setTimeout(TIMEOUT);
            collectionRequest.headers().set("Accept", "application/json");
            collectionRequest.headers().set(SELF_REQUEST_HEADER, "true");
            collectionRequest.setChunked(true);
            collectionRequest.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(request, parentUrl, MergeHandler.class));
            collectionRequest.send(asyncResult -> {
                HttpClientResponse collectionResponse = asyncResult.result();
                collectionResponse.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(request, parentUrl, MergeHandler.class));
                // everything is ok
                if (collectionResponse.statusCode() == StatusCode.OK.getStatusCode()) {
                    final String parentCollection = getCollectionName(parentUrl);

                    // do superfanzy things
                    collectionResponse.bodyHandler(data -> {
                        String collectionName = parentCollection;
                        JsonObject dataObject = new JsonObject(data.toString());

                        if (!dataObject.containsKey(collectionName)) {
                    /*
                        in case the parent collection can not be found, we
                        most surely have a request which is performed over a
                        route.
                        E.G.
                        Source: /gateleen/dynamicdata/
                        Target: /gateleen/target/t1/
                        GET /gateleen/dynamicdata/
                        {
                            "t1" : [
                                "..."
                            ]
                        }

                        This has to be handled in order to be able to perform
                        even root - GET requests.

                        In this case there may only exists (always) one Element
                        in the dataObject. This element we do need!
                     */
                            if (dataObject.size() == 1) {
                                collectionName = dataObject.fieldNames().stream().findFirst().get();

                                if (log.isTraceEnabled()) {
                                    log.trace("   >>> collection {} could not be found, use instead key: {}", parentCollection, collectionName);
                                }
                            }
                        }


                        if (log.isTraceEnabled()) {
                            log.trace("requestCollection >> uri is: {}, body is: {}", parentUrl, data.toString());
                        }


                        if (dataObject.getValue(collectionName) instanceof JsonArray) {
                            List<String> collectionContent = getCollectionContent(dataObject.getJsonArray(collectionName));

                            // collection
                            if (collectionContent.contains(targetUrlPart + SLASH)) {
                                mergeCollectionHandler.handle(new MergeData(
                                        data,
                                        collectionResponse.statusCode(),
                                        collectionResponse.statusMessage(),
                                        true,
                                        requestUrl));
                            }
                            // resource
                            else if (collectionContent.contains(targetUrlPart)) {
                                mergeCollectionHandler.handle(new MergeData(
                                        data,
                                        collectionResponse.statusCode(),
                                        collectionResponse.statusMessage(),
                                        false,
                                        requestUrl));
                            }
                            // not found
                            else {
                                mergeCollectionHandler.handle(new MergeData(
                                        data,
                                        StatusCode.NOT_FOUND.getStatusCode(),
                                        StatusCode.NOT_FOUND.getStatusMessage(),
                                        false,
                                        requestUrl));
                            }
                        }
                        // this is an optimization
                        else {
                            if (log.isTraceEnabled()) {
                                log.trace("requestCollection >> given array was not found");
                            }

                            mergeCollectionHandler.handle(new MergeData(
                                    data,
                                    StatusCode.NOT_FOUND.getStatusCode(),
                                    StatusCode.NOT_FOUND.getStatusMessage(),
                                    false,
                                    requestUrl));
                        }
                    });
                }
                // not found or something else is not ok
                else {
                    collectionResponse.handler(data -> {
                        mergeCollectionHandler.handle(new MergeData(
                                data,
                                collectionResponse.statusCode(),
                                collectionResponse.statusMessage(),
                                false,
                                requestUrl));
                    });
                }
            });
        });
    }

    /**
     * Returns a list of strings (with trailing slash) of
     * the collection passed down as an array.
     *
     * @param array jsonArray
     * @return list of collection strings
     */
    private List<String> getCollections(JsonArray array) {
        return ((List<Object>) array.getList()).stream()
                .map(r -> (String) r)
                .filter(r -> r.endsWith(SLASH))
                .collect(Collectors.toList());
    }

    /**
     * Returns a list of strings of the content from the
     * passed down collection.
     *
     * @param array jsonArray
     * @return list of content strings of the collection
     */
    private List<String> getCollectionContent(JsonArray array) {
        return ((List<Object>) array.getList()).stream()
                .map(r -> (String) r)
                .collect(Collectors.toList());
    }

    private Handler<MergeData> installMergeCollectionHandler(final HttpServerRequest request,
                                                             final int subResourcesCount,
                                                             final String targetUrlPart) {
        return new Handler<MergeData>() {
            private final List<MergeData> collectedData = new ArrayList<>();
            private final int totalCollectionCount = subResourcesCount;

            @Override
            public void handle(MergeData subCollectionData) {
                if (log.isTraceEnabled()) {
                    log.trace("mergeCollectionHandler - handle (count: {}) > {}", totalCollectionCount, subCollectionData.getTargetRequest());
                    log.trace(" >>> data: {}", subCollectionData.getContent().toString());
                }

                collectedData.add(subCollectionData);

                // we collected every subrequest, ready to proceed
                if (collectedData.size() == totalCollectionCount) {
                    if (log.isTraceEnabled()) {
                        log.trace("mergeCollectionHandler - handle > list complete, start processing");
                    }

                    /*
                     handle all possible error cases:

                     1) every found 'element' should of the same type (either resource or collection)
                     2) if nothing was found, break request and return 404 NOT - Found
                     3) if one element is neither 404, nor 200, return first non 4xx/2xx error
                      */

                    MergeData error = null;
                    int found = 0;
                    int collectionCount = 0;
                    int resourceCount = 0;
                    int notFound = 0;

                    for (MergeData data : collectedData) {
                        // found
                        if (data.getStatusCode() == StatusCode.OK.getStatusCode()) {
                            found++;

                            // collection?
                            if (data.isTargetCollection()) {
                                collectionCount++;
                            }
                            // resource
                            else {
                                resourceCount++;
                            }
                        } else if (data.getStatusCode() == StatusCode.NOT_FOUND.getStatusCode()) {
                            notFound++;
                        } else {
                            error = data;
                            break;
                        }
                    }

                    if (log.isTraceEnabled()) {
                        log.trace("mergeCollectionHandler - handle > error {}, found {}, collectionCount {}, resourceCount {}, notFound {}", error, found, collectionCount, resourceCount, notFound);
                    }

                    // no errors recorded, case 3) is fulfilled
                    if (error == null) {
                        if (log.isTraceEnabled()) {
                            log.trace("mergeCollectionHandler - handle > no errors found");
                        }

                        // nothing is found, case 2) is fulfilled
                        if (notFound == totalCollectionCount) {
                            if (log.isTraceEnabled()) {
                                log.trace("mergeCollectionHandler - handle > nothing found");
                            }

                            // return 404
                            createResponse(request,
                                    StatusCode.NOT_FOUND.getStatusCode(),
                                    StatusCode.NOT_FOUND.getStatusMessage(),
                                    null,
                                    null);
                        }
                        // process collection
                        else if (found == collectionCount && !collectedData.isEmpty()) {
                            if (log.isTraceEnabled()) {
                                log.trace("mergeCollectionHandler - handle > performMergeRequest");
                            }

                            final List<MergeData> subCollectionsToRequest = collectedData.stream()
                                    .filter(cData -> cData.getStatusCode() == StatusCode.OK.getStatusCode() &&
                                            cData.isTargetCollection())
                                    .collect(Collectors.toList());
                            final Handler<MergeData> mergeRequestHandler = installMergeRequestHandler(request, subCollectionsToRequest.size(), targetUrlPart);

                            for (final MergeData data : subCollectionsToRequest) {
                                performMergeRequest(request, data, mergeRequestHandler);
                            }
                        }
                        // process resource
                        else if (found == resourceCount && !collectedData.isEmpty()) {
                            if (log.isTraceEnabled()) {
                                log.trace("mergeCollectionHandler - handle > performDirectRequest");
                            }

                            // we only process the very first resource in a merge request!
                            final MergeData resource = collectedData.stream()
                                    .filter(cData -> cData.getStatusCode() == StatusCode.OK.getStatusCode() &&
                                            !cData.isTargetCollection())
                                    .findFirst()
                                    .get();
                            performDirectRequest(request, resource);
                        }
                        // missmatch, resources as well as collections with given name were found, case 1) is fulfilled
                        else {
                            if (log.isTraceEnabled()) {
                                log.trace("mergeCollectionHandler - handle > createResponse (missmatch)");
                            }

                            // return status 500
                            createResponse(request,
                                    StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                                    StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage(),
                                    null,
                                    MISSMATCH_ERROR);
                        }
                    }
                    // errors found
                    else {
                        if (log.isTraceEnabled()) {
                            log.trace("mergeCollectionHandler - handle > createResponse (error)");
                        }

                        // return given error code & data
                        createResponse(request,
                                error.getStatusCode(),
                                error.getStatusMessage(),
                                error.getContent(),
                                null);
                    }
                }
            }
        };
    }

    private void performMergeRequest(final HttpServerRequest request, final MergeData data, final Handler<MergeData> mergeRequestHandler) {
        final String uri = data.getTargetRequest() + getParameters(request.uri());

        if (log.isTraceEnabled()) {
            log.trace("performMergeRequest > {}, {}", data.getTargetRequest(), uri);
        }

        httpClient.request(HttpMethod.GET, uri).onComplete(asyncReqResult -> {
            if (asyncReqResult.failed()) {
                log.warn("Failed request to {}: {}", request.uri(), asyncReqResult.cause());
                return;
            }
            HttpClientRequest mergeRequest = asyncReqResult.result();

            mergeRequest.setTimeout(TIMEOUT);
            mergeRequest.headers().addAll(request.headers());
            mergeRequest.headers().set(SELF_REQUEST_HEADER, "true");
            mergeRequest.headers().remove(MERGE_HEADER);
            mergeRequest.setChunked(true);
            mergeRequest.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(request, data.getTargetRequest(), MergeHandler.class));
            mergeRequest.send(asyncResult -> {
                HttpClientResponse res = asyncResult.result();
                res.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(request, data.getTargetRequest(), MergeHandler.class));
                res.bodyHandler(buffer -> {
                    mergeRequestHandler.handle(new MergeData(buffer,
                            res.statusCode(),
                            res.statusMessage(),
                            true,
                            data.getTargetRequest()));
                });
            });
        });
    }

    private Handler<MergeData> installMergeRequestHandler(final HttpServerRequest request, final int size, final String targetUrlPart) {
        return new Handler<MergeData>() {
            private final List<MergeData> collectedData = new ArrayList<>();

            @Override
            public void handle(MergeData requestData) {
                collectedData.add(requestData);

                if (collectedData.size() == size) {
                    if (log.isTraceEnabled()) {
                        log.trace("installMergeRequestHandler > process started");
                    }

                    MergeData error = null;

                    for (MergeData data : collectedData) {
                        if (data.getStatusCode() != StatusCode.OK.getStatusCode() &&
                                data.getStatusCode() != StatusCode.NOT_FOUND.getStatusCode()) {
                            error = data;
                            break;
                        }
                    }

                    if (error == null) {
                        if (log.isTraceEnabled()) {
                            log.trace("installMergeRequestHandler > no error found");
                        }

                        final List<MergeData> validData = collectedData.stream()
                                .filter(cData -> cData.getStatusCode() == StatusCode.OK.getStatusCode())
                                .collect(Collectors.toList());

                        if (!validData.isEmpty()) {
                            if (log.isTraceEnabled()) {
                                log.trace("installMergeRequestHandler > createMergedResponse");
                            }

                            createMergedResponse(request, targetUrlPart, validData);
                        } else {
                            if (log.isTraceEnabled()) {
                                log.trace("installMergeRequestHandler > nothing found");
                            }

                            createResponse(request,
                                    StatusCode.NOT_FOUND.getStatusCode(),
                                    StatusCode.NOT_FOUND.getStatusMessage(),
                                    null,
                                    null);
                        }
                    } else {
                        if (log.isTraceEnabled()) {
                            log.trace("installMergeRequestHandler > error");
                        }

                        // return given error code & data
                        createResponse(request,
                                error.getStatusCode(),
                                error.getStatusMessage(),
                                error.getContent(),
                                null);
                    }
                }
            }
        };
    }

    /**
     * Merges the result of all collection requests to one request
     * and creates a valid response.
     *
     * @param request        the original request
     * @param collectionName the name of the resulting collection
     * @param collectionData the data which has to be merged to the given collection
     */
    private void createMergedResponse(final HttpServerRequest request, final String collectionName, final List<MergeData> collectionData) {
        if (log.isTraceEnabled()) {
            log.trace("createMergedResponse > {}", collectionName);
        }

        final Set<String> collectionContent = new HashSet<>();

        // merge all responses
        for (final MergeData data : collectionData) {
            final JsonObject dataObject = new JsonObject(data.getContent().toString());
            if (log.isTraceEnabled()) {
                log.trace("createMergedResponse > loop - {}", dataObject.toString());
            }

            if (dataObject.getValue(collectionName) instanceof JsonArray) {
                collectionContent.addAll(getCollectionContent(dataObject.getJsonArray(collectionName)));
            }
        }

        final JsonObject responseData = new JsonObject();
        final List<String> sortedCollectionContent = new ArrayList<>(collectionContent);
        sortedCollectionContent.sort(collectionContentComparator);
        responseData.put(collectionName, new JsonArray(sortedCollectionContent));


        /**
         * Because we create a new response (merged collection), we have
         * to set the header to the expected content type.
         */

        final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Content-Type", "application/json");

        // create response
        createResponse(request,
                StatusCode.OK.getStatusCode(),
                StatusCode.OK.getStatusMessage(),
                Buffer.buffer(responseData.toString()),
                null,
                headers);
    }

    /**
     * Performs a direct request to the given resourceData
     *
     * @param request      request
     * @param resourceData resourceData
     */
    private void performDirectRequest(final HttpServerRequest request, final MergeData resourceData) {
        final String uri = resourceData.getTargetRequest() + getParameters(request.uri());

        if (log.isTraceEnabled()) {
            log.trace("performDirectRequest > {}", uri);
        }

        httpClient.request(HttpMethod.GET, uri).onComplete(asyncReqResult -> {
            if (asyncReqResult.failed()) {
                log.warn("Failed request to {}: {}", request.uri(), asyncReqResult.cause());
                return;
            }
            HttpClientRequest directRequest = asyncReqResult.result();

            directRequest.setTimeout(TIMEOUT);
            directRequest.headers().addAll(request.headers());
            directRequest.headers().set(SELF_REQUEST_HEADER, "true");
            directRequest.headers().remove(MERGE_HEADER);
            directRequest.setChunked(true);
            directRequest.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(request, resourceData.getTargetRequest(), MergeHandler.class));
            directRequest.send(asyncResult -> {
                HttpClientResponse res = asyncResult.result();
                HttpServerRequestUtil.prepareResponse(request, res);

                res.handler(data -> request.response().write(data));
                res.endHandler(data -> request.response().end());
            });
        });
    }

    /**
     * Creates the final response to the original request.
     *
     * @param request       the original request
     * @param statusCode    the resulting status code
     * @param statusMessage the resulting status message
     * @param data          the data (may be null)
     * @param freetext      a freetext in case of an error (may be null)
     * @param headers       headers used for the response
     */
    private void createResponse(final HttpServerRequest request, final int statusCode, final String statusMessage, final Buffer data, final String freetext, final MultiMap headers) {
        if (log.isTraceEnabled()) {
            log.trace("createResponse - for -> {} with statusCode {}.", request.uri(), statusCode);
        }

        request.response().setStatusCode(statusCode);
        request.response().setStatusMessage(statusMessage);

        if (headers != null) {
            request.response().headers().addAll(headers);
        }

        request.response().setChunked(true);
        if (freetext != null) {
            request.response().end(freetext);
        } else if (data != null) {
            request.response().end(data);
        } else {
            request.response().end();
        }
    }

    /**
     * Creates the final response to the original request.
     *
     * @param request       the original request
     * @param statusCode    the resulting status code
     * @param statusMessage the resulting status message
     * @param data          the data (may be null)
     * @param freetext      a freetext in case of an error (may be null)
     */
    private void createResponse(final HttpServerRequest request, final int statusCode, final String statusMessage, final Buffer data, final String freetext) {
        createResponse(request, statusCode, statusMessage, data, freetext, null);
    }

    /**
     * Returns the last part of the url, always without trailing slash.
     *
     * @param requestUrl requestUrl
     * @return last part of url
     */
    private String getTargetUrlPart(String requestUrl) {
        if (requestUrl.endsWith(SLASH)) {
            requestUrl = requestUrl.substring(0, requestUrl.lastIndexOf(SLASH));
        }

        return requestUrl.substring(requestUrl.lastIndexOf(SLASH) + 1, requestUrl.length());
    }


    private String prepareParentCollection(String collection) {
        if (collection.endsWith(SLASH)) {
            collection = collection.substring(0, collection.lastIndexOf(SLASH));
        }

        return collection.substring(0, collection.lastIndexOf('/') + 1);
    }

    /**
     * Takes a collection url assuming the last part is the collection.
     * The name will be returned without any slashes.
     *
     * @param url collection
     * @return name without slash
     */
    private String getCollectionName(String url) {
        if (url.endsWith(SLASH)) {
            url = url.substring(0, url.lastIndexOf(SLASH));
        }

        return url.substring(url.lastIndexOf(SLASH) + 1, url.length());
    }


    /**
     * Returns the parameter string (?expand=....) if
     * parameters are available, otherwise an empty string
     * is returend.
     *
     * @param uri the request url
     * @return the parameters (if any) of the request url or an empty string
     */
    private String getParameters(String uri) {
        int parameterIndex = uri.lastIndexOf('?');
        if (parameterIndex == NO_PARAMETER_FOUND) {
            return "";
        }
        return uri.substring(parameterIndex, uri.length());
    }
}
