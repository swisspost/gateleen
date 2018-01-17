package org.swisspush.gateleen.expansion;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.CollectionResourceContainer;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.SlashHandling;
import org.swisspush.gateleen.core.util.ResourceCollectionException;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleFeaturesProvider;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.swisspush.gateleen.routing.RuleFeatures.Feature.EXPAND_ON_BACKEND;
import static org.swisspush.gateleen.routing.RuleFeatures.Feature.STORAGE_EXPAND;
import static org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;

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
public class ExpansionHandler implements RuleChangesObserver{
    private Logger log = LoggerFactory.getLogger(ExpansionHandler.class);

    public static final String SERIOUS_EXCEPTION = "a serious exception happend ";
    public static final String EXPAND_PARAM = "expand";
    public static final String ZIP_PARAM = "zip";

    private static final int NO_PARAMETER_FOUND = -1;
    private static final int START_INDEX = 0;
    private static final int TIMEOUT = 120000;

    private static final int DECREMENT_BY_ONE = 1;
    private static final int MAX_RECURSION_LEVEL = 0;

    public static final String MAX_EXPANSION_LEVEL_SOFT_PROPERTY = "max.expansion.level.soft";
    public static final String MAX_EXPANSION_LEVEL_HARD_PROPERTY = "max.expansion.level.hard";
    public static final String MAX_SUBREQUEST_PROPERTY = "max.expansion.subrequests";
    private static final int MAX_SUBREQUEST_COUNT_DEFAULT = 20000;

    private static final String ETAG_HEADER = "Etag";
    private static final String SELF_REQUEST_HEADER = "x-self-request";

    private int maxSubRequestCount;

    private int maxExpansionLevelSoft = Integer.MAX_VALUE;
    private int maxExpansionLevelHard = Integer.MAX_VALUE;

    private HttpClient httpClient;
    private Map<String, Object> properties;
    private String serverRoot;
    private RuleProvider ruleProvider;

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
        this.httpClient = httpClient;
        this.properties = properties;
        this.serverRoot = serverRoot;

        initParameterRemovalLists();
        initConfigurationValues();

        this.ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        this.ruleProvider.registerObserver(this);
    }

    @Override
    public void rulesChanged(List<Rule> rules) {
        log.info("Update expandOnBackend and storageExpand information from changed routing rules");
        ruleFeaturesProvider = new RuleFeaturesProvider(rules);
    }

    public int getMaxExpansionLevelSoft() {
        return maxExpansionLevelSoft;
    }

    public int getMaxExpansionLevelHard() {
        return maxExpansionLevelHard;
    }

    public int getMaxSubRequestCount() {
        return maxSubRequestCount;
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
                maxSubRequestCount = Integer.parseInt((String) properties.get(MAX_SUBREQUEST_PROPERTY));
                log.info("Setting maximum allowed subrequest count to " + maxSubRequestCount + " from properties");
            } catch (Exception e) {
                maxSubRequestCount = MAX_SUBREQUEST_COUNT_DEFAULT;
                log.warn("Setting maximum allowed subrequest count to a default of " + maxSubRequestCount + ", since defined value for " + MAX_SUBREQUEST_PROPERTY + " in properties is not a number");
            }
        } else {
            maxSubRequestCount = MAX_SUBREQUEST_COUNT_DEFAULT;
            log.warn("Setting maximum allowed subrequest count to a default of " + maxSubRequestCount + ", since no property " + MAX_SUBREQUEST_PROPERTY + " is defined!");
        }

        if (properties != null && properties.containsKey(MAX_EXPANSION_LEVEL_SOFT_PROPERTY)) {
            try {
                maxExpansionLevelSoft = Integer.parseInt((String) properties.get(MAX_EXPANSION_LEVEL_SOFT_PROPERTY));
                log.info("Setting maximum expansion level soft value to " + maxExpansionLevelSoft + " from properties");
            } catch (Exception e) {
                log.warn("Setting maximum expansion level soft value to a default of " + maxExpansionLevelSoft + ", since defined value for " + MAX_EXPANSION_LEVEL_SOFT_PROPERTY + " in properties is not a number");
            }
        } else {
            log.info("Setting maximum expansion level soft value to a default of " + maxExpansionLevelSoft + ", since no property " + MAX_EXPANSION_LEVEL_SOFT_PROPERTY + " is defined!");
        }

        if (properties != null && properties.containsKey(MAX_EXPANSION_LEVEL_HARD_PROPERTY)) {
            try {
                maxExpansionLevelHard = Integer.parseInt((String) properties.get(MAX_EXPANSION_LEVEL_HARD_PROPERTY));
                log.info("Setting maximum expansion level hard value to " + maxExpansionLevelHard + " from properties");
            } catch (Exception e) {
                log.warn("Setting maximum expansion level hard value to a default of " + maxExpansionLevelHard + ", since defined value for " + MAX_EXPANSION_LEVEL_HARD_PROPERTY + " in properties is not a number");
            }
        } else {
            log.info("Setting maximum expansion level soft hard to a default of " + maxExpansionLevelHard + ", since no property " + MAX_EXPANSION_LEVEL_HARD_PROPERTY + " is defined!");
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
            ok = ! request.params().get(ZIP_PARAM).equalsIgnoreCase("false");
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
        Integer expandLevel = extractExpandParamValue(req, log);

        if(expandLevel == null){
            respondBadRequest(req,"Expand parameter is not valid. Must be a positive number");
            return;
        }

        if(expandLevel > maxExpansionLevelHard){
            String message = "Expand level '"+expandLevel+"' is greater than the maximum expand level '" + maxExpansionLevelHard + "'";
            log.info(message);
            respondBadRequest(req, message);
            return;
        }

        if(expandLevel > maxExpansionLevelSoft){
            log.warn("Expand level '"+expandLevel+"' is greater than the maximum soft expand level '" +
                    maxExpansionLevelSoft + "'. Using '"+maxExpansionLevelSoft+"' instead");
            expandLevel = maxExpansionLevelSoft;
        }

        if(isStorageExpand(req.uri()) && expandLevel > 1){
            respondBadRequest(req, "Expand values higher than 1 are not supported for storageExpand requests");
            return;
        }

        // store the parameters for later use
        Set<String> originalParams = null;
        if (req.params() != null) {
            originalParams = req.params().names();
        }
        final Set<String> finalOriginalParams = originalParams;

        final String targetUri = ExpansionDeltaUtil.constructRequestUri(req.path(), req.params(), parameter_to_remove_for_all_request, null, SlashHandling.END_WITH_SLASH);
        log.debug("constructed uri for request: " + targetUri);

        Integer finalExpandLevel = expandLevel;
        final HttpClientRequest cReq = httpClient.request(HttpMethod.GET, targetUri, cRes -> {
            req.response().setStatusCode(cRes.statusCode());
            req.response().setStatusMessage(cRes.statusMessage());
            req.response().headers().setAll(cRes.headers());
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
                makeResourceSubRequest(targetUri, req, finalExpandLevel, new AtomicInteger(),
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
        cReq.headers().set(SELF_REQUEST_HEADER, "true");
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
            log.debug("Request done");
        });
        cReq.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(req, targetUri, ExpansionHandler.class));

        if (log.isTraceEnabled()) {
            log.trace("resume request");
        }
        req.resume();
    }

    private Integer extractExpandParamValue(final HttpServerRequest request, final Logger log){
        String expandValue = request.params().get(EXPAND_PARAM);
        log.debug("got expand parameter value " + expandValue);

        try {
            Integer value = Integer.valueOf(expandValue);
            if(value < 0){
                log.warn("expand parameter value '"+expandValue+"' is not a positive number");
                return null;
            }
            return value;
        } catch (NumberFormatException ex){
            log.warn("expand parameter value '"+expandValue+"' is not a valid number");
            return null;
        }
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
        // default
        RecursiveHandlerFactory.RecursiveHandlerTypes zipType = RecursiveHandlerFactory.RecursiveHandlerTypes.ZIP;

        try {
            // override (eg. store)
            zipType = RecursiveHandlerFactory.RecursiveHandlerTypes.valueOf(request.params().get(ZIP_PARAM).toUpperCase());
        }
        catch( Exception handled ) {
        }

        if ( log.isTraceEnabled() ) {
            log.trace("currently using zip mode: " + zipType);
        }

        removeZipParameter(request);
        handleExpansionRequest(request, zipType);
    }

    /**
     * Removes the zip parameter, it's only needed by the check
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
        cReq.headers().set(SELF_REQUEST_HEADER, "true");
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
         * created and the recursive get process is canceled.
         */
        if (subRequestCounter.get() > maxSubRequestCount) {
            handler.handle(new ResourceNode(SERIOUS_EXCEPTION, new ResourceCollectionException("Number of allowed sub requests exceeded. Limit is " + maxSubRequestCount + " requests", StatusCode.BAD_REQUEST)));
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
        cReq.headers().set(SELF_REQUEST_HEADER, "true");
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
     * Respond the request with a statuscode {@link StatusCode#BAD_REQUEST} and body.
     *
     * @param request the request to respond to
     * @param body the body to respond
     */
    private void respondBadRequest(final HttpServerRequest request, String body){
        ResponseStatusCodeLogUtil.info(request, StatusCode.BAD_REQUEST, ExpansionHandler.class);
        request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
        request.response().end(body);
        request.resume();
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
