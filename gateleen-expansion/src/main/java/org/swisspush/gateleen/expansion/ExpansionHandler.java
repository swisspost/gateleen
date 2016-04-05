package org.swisspush.gateleen.expansion;

import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.*;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.CollectionResourceContainer;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.SlashHandling;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleFactory;
import org.swisspush.gateleen.routing.RuleFeaturesProvider;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.swisspush.gateleen.routing.RuleFeatures.Feature.EXPAND_ON_BACKEND;
import static org.swisspush.gateleen.routing.RuleFeatures.Feature.STORAGE_EXPAND;

/**
 * The {@link ExpansionHandler} allows to fetch multiple a collection of multiple resources in
 * a single GET request. To use the {@link ExpansionHandler} the parameter {@code expand=x} has to be added.
 * Having a request to a resource defining a collection of resources like this:
 *
 * <pre>
 * {"acls":["admin","developer"]}
 * </pre>
 *
 * Would lead to a result like this:
 *
 * <pre>
 * {
 *  "acls" : {
 *    "admin" : {
 *      "all.edit": {
 *        "methods": [
 *          "GET",
 *          "PUT",
 *          "POST",
 *          "DELETE"
 *        ],
 *        "path": "/.*"
 *      }
 *    },
 *    "developer" : {
 *      "role_1.menu.display": {},
 *      "role_2.menu.display": {},
 *      "gateleen.admin.menu": {}
 *    }
 *  }
 * }
 * </pre>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber], https://github.com/ljucam [Mario Ljuca]
 */
public class ExpansionHandler {
    private Logger log = LoggerFactory.getLogger(ExpansionHandler.class);

    public static final String SERIOUS_EXCEPTION = "a serious exception happend ";
    public static final String EXPAND_PARAM = "expand";
    public static final String ZIP_PARAM = "zip";

    private static final int NO_PARAMETER_FOUND = -1;
    private static final int START_INDEX = 0;
    private static final int TIMEOUT = 120000;

    private static final int DECREMENT_BY_ONE = 1;
    private static final int MAX_RECURSION_LEVEL = 0;
    private static final String MAX_RECURSION_DEPTH_PROPERTY = "max.recursion.depth";
    private static final int RECURSION_DEPTH_DEFAULT = 2000;

    private static final String MAX_SUBREQUEST_PROPERTY = "max.expansion.subrequests";
    private static final int MAX_SUBREQUEST_COUNT_DEFAULT = 20000;

    private static final String ETAG_HEADER = "Etag";
    private static final String SELF_REQUEST_HEADER = "x-self-request";

    private int maxRecursionDepth;
    private int maxSubrequestCount;

    private HttpClient httpClient;
    private ResourceStorage storage;
    private Map<String, Object> properties;
    private String serverRoot;
    private String rulesPath;

    private String routingRulesSchema;

    /**
     * A list of parameters, which are always removed
     * from all requests.
     */
    private List<String> parameter_to_remove_for_all_request;

    /**
     * A list of parameters, which are removed after the
     * initial request.
     */
    private List<String> parameter_to_remove_after_initial_request;

    private RuleFeaturesProvider ruleFeaturesProvider = new RuleFeaturesProvider(new ArrayList<>());

    /**
     * Creates a new instance of the ExpansionHandler.
     *
     * @param vertx vertx
     * @param storage storage
     * @param httpClient httpClient
     * @param properties properties
     * @param serverRoot serverRoot
     * @param rulesPath rulesPath
     */
    public ExpansionHandler(Vertx vertx, final ResourceStorage storage, HttpClient httpClient, final Map<String, Object> properties, String serverRoot, final String rulesPath) {
        this.storage = storage;
        this.httpClient = httpClient;
        this.properties = properties;
        this.serverRoot = serverRoot;
        this.rulesPath = rulesPath;

        routingRulesSchema = ResourcesUtils.loadResource("gateleen_routing_schema_routing_rules", true);

        initParameterRemovalLists();
        initConfigurationValues();

        loadRulesFromStorage();

        // Receive update notifications
        log.info("ExpansionHandler - register on vertx bus to receive routing rules updates");
        vertx.eventBus().consumer(Address.RULE_UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> loadRulesFromStorage());
    }

    private void loadRulesFromStorage(){
        storage.get(rulesPath, buffer -> {
            if (buffer != null) {
                try {
                    List<Rule> rules = new RuleFactory(properties, routingRulesSchema).parseRules(buffer);
                    log.info("Update expandOnBackend and storageExpand information from changed routing rules");
                    ruleFeaturesProvider = new RuleFeaturesProvider(rules);
                } catch (ValidationException e) {
                    log.error("Could not update expandOnBackend and storageExpand information from changed routing rules", e);
                }
            } else {
                log.warn("Could not get URL '" + (rulesPath == null ? "<null>" : rulesPath) + "' (getting rules).");
            }
        });
    }

    /**
     * Initialize the lists which defines, when which parameter
     * is removed (if any).
     */
    private void initParameterRemovalLists() {
        parameter_to_remove_for_all_request = new ArrayList<>();
        parameter_to_remove_after_initial_request = new ArrayList<>();

        /*
         * Parameter which have to be removed for
         * all requests
         * -----
         */
        parameter_to_remove_for_all_request.add(EXPAND_PARAM);
        // -----

        /*
         * Parameter which have to be removed after
         * the initial request is done
         * -----
         */
        parameter_to_remove_after_initial_request.addAll(parameter_to_remove_for_all_request);
        parameter_to_remove_after_initial_request.add("limit");
        parameter_to_remove_after_initial_request.add("offset");
        // -----
    }

    /**
     * Initialize the configuration values.
     */
    private void initConfigurationValues() {
        if (properties != null && properties.containsKey(MAX_SUBREQUEST_PROPERTY)) {
            try {
                maxSubrequestCount = Integer.parseInt((String) properties.get(MAX_SUBREQUEST_PROPERTY));
                log.info("Setting maximum allowed subrequest count to " + maxSubrequestCount + " from properties");
            } catch (Exception e) {
                maxSubrequestCount = MAX_SUBREQUEST_COUNT_DEFAULT;
                log.warn("Setting maximum allowed subrequest count to a default of " + maxSubrequestCount + ", since defined value for " + MAX_SUBREQUEST_PROPERTY + " in properties is not a number");
            }
        } else {
            maxSubrequestCount = MAX_SUBREQUEST_COUNT_DEFAULT;
            log.warn("Setting maximum allowed subrequest count to a default of " + maxSubrequestCount + ", since no property " + MAX_SUBREQUEST_PROPERTY + " is defined!");
        }

        if (properties != null && properties.containsKey(MAX_RECURSION_DEPTH_PROPERTY)) {
            try {
                maxRecursionDepth = Integer.parseInt((String) properties.get(MAX_RECURSION_DEPTH_PROPERTY));
                log.info("Setting default recursion depth to " + maxRecursionDepth + " from properties");
            } catch (Exception e) {
                maxRecursionDepth = RECURSION_DEPTH_DEFAULT;
                log.warn("Setting default recursion depth to a default of " + maxRecursionDepth + ", since defined value for " + MAX_RECURSION_DEPTH_PROPERTY + " in properties is not a number");
            }
        } else {
            maxRecursionDepth = RECURSION_DEPTH_DEFAULT;
            log.warn("Setting default recursion depth to a default of " + maxRecursionDepth + ", since no property " + MAX_RECURSION_DEPTH_PROPERTY + " is defined!");
        }
    }

    /**
     * Returns true when the {@link ExpansionHandler} can deal with the given request.
     * The request must be a GET request and must have the parameter {@code expand=x}.
     * The method called after this check must be <code>handleExpansionRecursion( ... )</code>.
     *
     * @param request request
     * @return boolean
     */
    public boolean isExpansionRequest(HttpServerRequest request) {
        return HttpMethod.GET == request.method() && request.params().contains(EXPAND_PARAM) && !isBackendExpand(request.uri());
    }

    /**
     * Check to see whether this request will be expanded by the backend (and therefore the expansionhandler
     * won't do anything).
     *
     * @param uri uri to check against the internal list of expandOnBackend urls
     * @return boolean and true if the expand should be done by the backend
     */
    protected boolean isBackendExpand(String uri) {
        return ruleFeaturesProvider.isFeatureRequest(EXPAND_ON_BACKEND, uri);
    }

    /**
     * Check to see whether this request is a storageExpand request (will be expanded by in the storage) (and therefore the expansionhandler
     * won't do the subrequests by itself).
     *
     * @param uri uri to check against the internal list of storageExpand urls
     * @return boolean and true if the expand should be done in the storage
     */
    protected boolean isStorageExpand(String uri) {
        return ruleFeaturesProvider.isFeatureRequest(STORAGE_EXPAND, uri);
    }

    /**
     * Returns true when the {@link ExpansionHandler} can deal with the given request.
     * The request must be a GET request and must have the parameter {@code expand=x&zip=true}.
     * The method called after this check must be <code>handleZipRecursion( ... )</code>.
     *
     * @param request request
     * @return boolean
     */
    public boolean isZipRequest(HttpServerRequest request) {
        boolean ok = false;
        if (HttpMethod.GET == request.method() && request.params().contains(ZIP_PARAM)) {
            ok = Boolean.valueOf(request.params().get(ZIP_PARAM));
        }

        return ok && !isBackendExpand(request.uri());
    }

    /**
     * Makes a request to get the collection with the names of the resources to fetch.
     * A result of this first request could look like this:
     *
     * <pre>
     * {"acls":["admin","developer"]}
     * </pre>
     *
     * In this example the name of the collection is <b>acls</b>.
     *
     * @param req - the request of the collection to fetch
     * @param recursiveHandlerType - the desired typ of the recursion functionality
     */
    private void handleExpansionRequest(final HttpServerRequest req, final RecursiveHandlerFactory.RecursiveHandlerTypes recursiveHandlerType) {
        req.pause();

        Logger log = RequestLoggerFactory.getLogger(ExpansionHandler.class, req);

        // store the parameters for later use
        // ----
        Set<String> originalParams = null;
        if (req.params() != null) {
            originalParams = req.params().names();
        }
        final Set<String> finalOriginalParams = originalParams;
        // ----

        /*
         * in order to get the recursion level,
         * we need to call getRecursionDepth before
         * constructing the uri. The reason lies in
         * the referenced multimap (params), passed
         * to the construction method. She empties
         * the multimap and therefore we don't have
         * the possibility anymore to get the wished
         * parameter!
         */
        final int recursionDepth = getRecursionDepth(req, log);

        if(isStorageExpand(req.uri()) && recursionDepth > 1){
            req.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
            req.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
            req.response().end("Expand values higher than 1 are not supported for storageExpand requests");
            req.resume();
            return;
        }

        final String targetUri = ExpansionDeltaUtil.constructRequestUri(req.path(), req.params(), parameter_to_remove_for_all_request, null, SlashHandling.END_WITH_SLASH);
        log.debug("constructed uri for request: " + targetUri);

        final HttpClientRequest cReq = httpClient.request(HttpMethod.GET, targetUri, cRes -> {
            req.response().setStatusCode(cRes.statusCode());
            req.response().setStatusMessage(cRes.statusMessage());
            req.response().headers().addAll(cRes.headers());
            req.response().headers().remove("Content-Length");
            req.response().setChunked(true);

            if (log.isTraceEnabled()) {
                log.trace(" x-delta for " + targetUri + " is " + cRes.headers().get("x-delta"));
            }

            cRes.bodyHandler(data -> {

                    /*
                     * TODO:
                     * We need the possibility define parameters, which are
                     * only used for the very FIRST request.
                     * After the first request they must be removed.
                     */

                    /*
                     * start the recursive get
                     * in order to guarantee, that not
                     * endless request are made (for
                     * example because of a loop),
                     * the count of the subrequests is
                     * limited. If this limit is reached
                     * an exception is thrown and handled
                     * by the handler right away.
                     */
                makeResourceSubRequest(targetUri, req, recursionDepth, new AtomicInteger(),
                        recursiveHandlerType,
                        RecursiveHandlerFactory.createRootHandler(recursiveHandlerType, req, serverRoot, data, finalOriginalParams), true);
            });
            cRes.exceptionHandler(ExpansionDeltaUtil.createResponseExceptionHandler(req, targetUri, ExpansionHandler.class));
        });

        if (log.isTraceEnabled()) {
            log.trace("set cReq headers");
        }
        cReq.setTimeout(TIMEOUT);
        cReq.headers().setAll(req.headers());
        cReq.headers().set("Accept", "application/json");
        cReq.headers().set(SELF_REQUEST_HEADER, "");
        cReq.setChunked(true);

        if (log.isTraceEnabled()) {
            log.trace("set request data handler");
        }
        req.handler(data -> {
            if (log.isTraceEnabled()) {
                log.trace("write data of the last subrequest");
            }
            cReq.write(data);
        });

        if (log.isTraceEnabled()) {
            log.trace("set request end handler");
        }
        req.endHandler(v -> {
            cReq.end();
            log.debug("Request done. Request : " + cReq);
        });
        cReq.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(req, targetUri, ExpansionHandler.class));

        if (log.isTraceEnabled()) {
            log.trace("resume request");
        }
        req.resume();
    }

    /**
     * Tries to extract the recursion depth from the
     * request parameter 'expand'. If the value cannot
     * be extracted a default value is returned.
     * 
     * @param req req
     * @return int
     */
    private int getRecursionDepth(final HttpServerRequest req, final Logger log) {
        String expandValue = req.params().get(EXPAND_PARAM);
        log.debug("expandValue = " + expandValue);
        int depth;

        try {
            depth = Integer.valueOf(expandValue);
        } catch (Exception e) {
            depth = maxRecursionDepth;
        }

        return depth;
    }

    /**
     * Handles the request as if it is a recursive expansion.
     * 
     * @param request request
     */
    public void handleExpansionRecursion(final HttpServerRequest request) {
        removeZipParameter(request);
        handleExpansionRequest(request, RecursiveHandlerFactory.RecursiveHandlerTypes.EXPANSION);
    }

    /**
     * Handles the request as if it is a request for a zip stream.
     * 
     * @param request request
     */
    public void handleZipRecursion(final HttpServerRequest request) {
        removeZipParameter(request);
        handleExpansionRequest(request, RecursiveHandlerFactory.RecursiveHandlerTypes.ZIP);
    }

    /**
     * Removes the zip parametet, it's only needed by the check
     * method <code>isZipRequest(...)</code>.
     * 
     * @param request request
     */
    private void removeZipParameter(final HttpServerRequest request) {
        if (request.params().contains(ZIP_PARAM)) {
            request.params().remove(ZIP_PARAM);
        }
    }

    private void makeStorageExpandRequest(final String targetUri, final List subResourceNames, final HttpServerRequest req, final DeltaHandler<ResourceNode> handler){
        Logger log = RequestLoggerFactory.getLogger(ExpansionHandler.class, req);
        final HttpClientRequest cReq = httpClient.request(HttpMethod.POST, targetUri + "?storageExpand=true", cRes -> {
            cRes.bodyHandler(data -> {
                if (StatusCode.NOT_FOUND.getStatusCode() == cRes.statusCode()) {
                    log.debug("requested resource could not be found: " + targetUri);
                    handler.handle(new ResourceNode(SERIOUS_EXCEPTION, new ResourceCollectionException(cRes.statusMessage(), StatusCode.NOT_FOUND)));
                } else if (StatusCode.INTERNAL_SERVER_ERROR.getStatusCode() == cRes.statusCode()) {
                    log.error("error in request resource : " + targetUri + " message : " + data.toString());
                    handler.handle(new ResourceNode(SERIOUS_EXCEPTION, new ResourceCollectionException(data.toString(), StatusCode.INTERNAL_SERVER_ERROR)));
                } else if (StatusCode.METHOD_NOT_ALLOWED.getStatusCode() == cRes.statusCode()) {
                    log.error("POST requests (storageExpand) not allowed for uri: " + targetUri);
                    handler.handle(new ResourceNode(SERIOUS_EXCEPTION, new ResourceCollectionException(cRes.statusMessage(), StatusCode.METHOD_NOT_ALLOWED)));
                } else {
                    String eTag = geteTag(cRes.headers());
                    handleSimpleResource(removeParameters(targetUri), handler, data, eTag);
                }
            });
        });

        JsonObject requestPayload = new JsonObject();
            requestPayload.put("subResources", new JsonArray(subResourceNames));
        Buffer payload = Buffer.buffer(requestPayload.encodePrettily());

        cReq.setTimeout(TIMEOUT);
        cReq.headers().setAll(req.headers());
        cReq.headers().set(SELF_REQUEST_HEADER, "");
        cReq.headers().set("Content-Type", "application/json; charset=utf-8");
        cReq.headers().set("Content-Length", "" + payload.length());
        cReq.setChunked(false);
        cReq.write(payload);

        cReq.end();
    }

    /**
     * Performs a recursive, asynchronous GET operation on the given uri.
     * 
     * @param targetUri - uri for creating a new request
     * @param req - the original request
     * @param recursionLevel - the actual depth of the recursion
     * @param subRequestCounter - the request counter
     * @param recursionHandlerType - the type of the desired handler
     * @param handler - the parent handler
     * @param collection - indicates if the just passed targetUri belongs to a collection or a resource
     */
    private void makeResourceSubRequest(final String targetUri, final HttpServerRequest req, final int recursionLevel, final AtomicInteger subRequestCounter, final RecursiveHandlerFactory.RecursiveHandlerTypes recursionHandlerType, final DeltaHandler<ResourceNode> handler, final boolean collection) {

        Logger log = RequestLoggerFactory.getLogger(ExpansionHandler.class, req);

        /*
         * each call of this method creates a self request.
         * In order not to exceed the limit of allowed
         * subrequests, every call increments the subRequestCounter.
         * If the subRequestCounter reaches maxSubrequestCount, an exception is
         * created and the recursive get process is canceld.
         */
        if (subRequestCounter.get() > maxSubrequestCount) {
            handler.handle(new ResourceNode(SERIOUS_EXCEPTION, new ResourceCollectionException("Number of allowed sub requests exceeded. Limit is " + maxSubrequestCount + " requests", StatusCode.BAD_REQUEST)));
            return;
        }

        subRequestCounter.incrementAndGet();

        // request target uri
        final HttpClientRequest cReq = httpClient.request(HttpMethod.GET, targetUri, cRes -> {

            if (log.isTraceEnabled()) {
                log.trace(" x-delta for " + targetUri + " is " + cRes.headers().get("x-delta"));
            }

            handler.storeXDeltaResponseHeader(cRes.headers().get("x-delta"));

            cRes.bodyHandler(data -> {
                /*
                 * extract eTag from response, it can be used for the collection, as well as the resource
                 */
                String eTag = geteTag(cRes.headers());

                if (StatusCode.NOT_FOUND.getStatusCode() == cRes.statusCode()) {
                    log.debug("requested resource could not be found: " + targetUri);
                    handler.handle(new ResourceNode(SERIOUS_EXCEPTION, new ResourceCollectionException(cRes.statusMessage(), StatusCode.NOT_FOUND)));
                } else if (StatusCode.INTERNAL_SERVER_ERROR.getStatusCode() == cRes.statusCode()) {
                    log.debug("error in request resource : " + targetUri);
                    handler.handle(new ResourceNode(SERIOUS_EXCEPTION, new ResourceCollectionException(cRes.statusMessage(), StatusCode.INTERNAL_SERVER_ERROR)));
                } else {
                    /*
                     * If the request is marked as a request for a collection,
                     * the handler for collections is invoked.
                     * This handler can throw an exception if the indicated request
                     * doesn't point to a collection.
                     * This can only happen at the beginning of the recursion and
                     * indicates the incorrect use of the parameter "expand". In
                     * this case the handling is passed to the simple resource handler,
                     * which is capable of even handling exceptions.
                     */
                    if (collection) {
                        try {
                            handleCollectionResource(removeParameters(targetUri), req, recursionLevel, subRequestCounter, recursionHandlerType, handler, data, eTag);
                        } catch (ResourceCollectionException e) {
                            if (log.isTraceEnabled()) {
                                log.trace("handling collection failed with: " + e.getMessage());
                            }
                            handleSimpleResource(removeParameters(targetUri), handler, data, eTag);
                        }
                    } else {
                        handleSimpleResource(removeParameters(targetUri), handler, data, eTag);
                    }
                }
            });
        });

        if (log.isTraceEnabled()) {
            log.trace("set the cReq headers for the subRequest");
        }
        cReq.setTimeout(TIMEOUT);
        cReq.headers().setAll(req.headers());
        cReq.headers().set("Accept", "application/json");
        cReq.headers().set(SELF_REQUEST_HEADER, "");
        cReq.setChunked(true);

        cReq.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(req, targetUri, ExpansionHandler.class));

        if (log.isTraceEnabled()) {
            log.trace("end the cReq for the subRequest");
        }
        cReq.end();
    }

    /**
     * Handles the response data for creating a simple resource with content.
     * 
     * @param targetUri - uri for creating a new request
     * @param handler - the parent handler
     * @param data - the data from the response of the request
     * @param eTag - eTag of the actual request
     */
    private void handleSimpleResource(final String targetUri, final Handler<ResourceNode> handler, final Buffer data, final String eTag) {
        String resourceName = ExpansionDeltaUtil.extractCollectionFromPath(targetUri);
        if (log.isTraceEnabled()) {
            log.trace("Simple resource: " + resourceName);
        }

        // pure data is passed to the handler
        handler.handle(new ResourceNode(resourceName, data, eTag, targetUri));
    }

    /**
     * Removes all parameters from the targetUri.
     * 
     * @param targetUri targetUri
     * @return String
     */
    private String removeParameters(String targetUri) {
        int parameterIndex = targetUri.lastIndexOf('?');
        if (parameterIndex != NO_PARAMETER_FOUND) {
            return targetUri.substring(START_INDEX, parameterIndex);
        }

        return targetUri;
    }

    /**
     * Handles the response data for creating a collection resource.
     * 
     * @param targetUri - uri for creating a new request
     * @param req - the original request
     * @param recursionLevel - the actual depth of the recursion
     * @param subRequestCounter - the request counter
     * @param recursionHandlerType - the typ of the desired handler
     * @param handler - the parent handler
     * @param data - the data from the response of the request
     * @param eTag - eTag of the actual request
     * @throws ResourceCollectionException - thrown if the response does not belong to a collection
     */
    private void handleCollectionResource(final String targetUri, final HttpServerRequest req, final int recursionLevel, final AtomicInteger subRequestCounter, final RecursiveHandlerFactory.RecursiveHandlerTypes recursionHandlerType, final DeltaHandler<ResourceNode> handler, final Buffer data, final String eTag) throws ResourceCollectionException {
        CollectionResourceContainer collectionResourceContainer = ExpansionDeltaUtil.verifyCollectionResponse(targetUri, data, null);
        Logger log = RequestLoggerFactory.getLogger(ExpansionHandler.class, req);
        if (log.isTraceEnabled()) {
            log.trace("Collection resource: " + collectionResourceContainer.getCollectionName());
            log.trace("actual recursion level: " + recursionLevel);
        }

        // list of all subresources (if any)
        List<String> subResourceNames = collectionResourceContainer.getResourceNames();

        if (subResourceNames.size() == 0) {
            if (log.isTraceEnabled()) {
                log.trace("No sub resource available for: " + collectionResourceContainer.getCollectionName());
            }

            ResourceNode node = new ResourceNode(collectionResourceContainer.getCollectionName(), new JsonArray(), eTag);
            handler.handle(node);
        } else {
            boolean maxRecursionLevelReached = recursionLevel == MAX_RECURSION_LEVEL;

            // normal processing
            if (!maxRecursionLevelReached) {
                if (log.isTraceEnabled()) {
                    log.trace("max. recursion reached for " + collectionResourceContainer.getCollectionName());
                }

                final DeltaHandler<ResourceNode> parentHandler = RecursiveHandlerFactory.createHandler(recursionHandlerType, subResourceNames, collectionResourceContainer.getCollectionName(), eTag, handler);

                if(isStorageExpand(targetUri)){
                    makeStorageExpandRequest(targetUri, subResourceNames, req, handler);
                } else {
                    for (String childResourceName : subResourceNames) {
                        if (log.isTraceEnabled()) {
                            log.trace("processing child resource: " + childResourceName);
                        }

                        // if the child is not a collection, we remove the parameter
                        boolean collection = isCollection(childResourceName);

                        final String collectionURI = ExpansionDeltaUtil.constructRequestUri(targetUri, req.params(), parameter_to_remove_after_initial_request, childResourceName, SlashHandling.END_WITHOUT_SLASH);
                        makeResourceSubRequest((collection ? collectionURI : removeParameters(collectionURI)), req, recursionLevel - DECREMENT_BY_ONE, subRequestCounter, recursionHandlerType, parentHandler, collection);
                    }
                }
            }
            // max. level reached
            else {
                JsonArray jsonArray = new JsonArray();

                for (String childResourceName : subResourceNames) {
                    if (log.isTraceEnabled()) {
                        log.trace("(max level reached) processing child resource: " + childResourceName);
                    }

                    jsonArray.add(childResourceName);
                }

                handler.handle(new ResourceNode(collectionResourceContainer.getCollectionName(), jsonArray, eTag));
            }
        }
    }

    /**
     * Returns true if the given name or path belongs
     * to a collection.
     * In this case ends with a slash.
     * 
     * @param target target
     * @return boolean
     */
    public boolean isCollection(String target) {
        return target.endsWith("/");
    }

    /**
     * Extracts the eTag from the given headers. <br />
     * If none eTag is found, an empty string is returned.
     * 
     * @param headers headers
     * @return String
     */
    private String geteTag(MultiMap headers) {
        return headers != null && headers.contains(ETAG_HEADER) ? headers.get(ETAG_HEADER) : "";
    }
}
