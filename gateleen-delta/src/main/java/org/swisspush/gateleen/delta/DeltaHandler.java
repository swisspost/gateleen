package org.swisspush.gateleen.delta;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.*;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.CollectionResourceContainer;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.SlashHandling;
import org.swisspush.gateleen.routing.Router;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DeltaHandler {

    private static final String DELTA_PARAM = "delta";
    private static final String DELTA_HEADER = "x-delta";
    private static final String IF_NONE_MATCH_HEADER = "if-none-match";
    // used as marker header to know that we should let the request continue to the router
    private static final String DELTA_BACKEND_HEADER = "x-delta-backend";
    private static final String EXPIRE_AFTER_HEADER = "X-Expire-After";
    private static final String SLASH = "/";
    private static final int TIMEOUT = 120000;

    private static final String SEQUENCE_KEY = "delta:sequence";
    private static final String RESOURCE_KEY_PREFIX = "delta:resources";
    private static final String ETAG_KEY_PREFIX = "delta:etags";

    private HttpClient httpClient;
    private RedisClient redisClient;

    public DeltaHandler(RedisClient redisClient, HttpClient httpClient) {
        this.redisClient = redisClient;
        this.httpClient = httpClient;
    }

    public boolean isDeltaRequest(HttpServerRequest request) {
        return isDeltaGETRequest(request) || isDeltaPUTRequest(request);
    }

    private boolean isDeltaPUTRequest(HttpServerRequest request) {
        if (HttpMethod.PUT == request.method() && request.headers().contains(DELTA_HEADER)) {
            return "auto".equalsIgnoreCase(request.headers().get(DELTA_HEADER));
        }
        return false;
    }

    private boolean isDeltaGETRequest(HttpServerRequest request) {
        if(HttpMethod.GET == request.method() &&
           request.params().contains(DELTA_PARAM) && 
           !request.headers().contains(DELTA_BACKEND_HEADER)) {
        	return true;
        }
        // remove the delta backend header, its only a marker
        if(request.headers().contains(DELTA_BACKEND_HEADER)) {
        	request.headers().remove(DELTA_BACKEND_HEADER);
        }
        return false;
    }

    public void handle(final HttpServerRequest request, Router router) {
        Logger log = RequestLoggerFactory.getLogger(DeltaHandler.class, request);
        if (isDeltaPUTRequest(request)) {
            handleResourcePUT(request, router, log);
        }
        if (isDeltaGETRequest(request)) {
            String updateId = extractStringDeltaParameter(request, log);
            if (updateId != null) {
                handleCollectionGET(request, updateId, log);
            }
        }
    }

    private void handleResourcePUT(final HttpServerRequest request, final Router router, final Logger log) {
        request.pause(); // pause the request to avoid problems with starting another async request (storage)
        handleDeltaEtag(request, log, updateDelta -> {
            if(updateDelta){
                // increment and get update-id
                redisClient.incr(SEQUENCE_KEY, reply -> {
                    if(reply.failed()){
                        log.error("incr command for redisKey {} failed with cause: {}", SEQUENCE_KEY, logCause(reply));
                        handleError(request, "error incrementing/accessing sequence for update-id");
                        return;
                    }

                    final String resourceKey = getResourceKey(request.path(), false);
                    Long expireAfter = getExpireAfterValue(request, log);
                    String updateId = reply.result().toString();

                    // save to storage
                    saveDelta(resourceKey, updateId, expireAfter, event -> {
                        if(event.failed()){
                            log.error("setex command for redisKey {} failed with cause: {}", resourceKey, logCause(event));
                            handleError(request, "error saving delta information");
                            request.resume();
                        } else {
                            request.resume();
                            router.route(request);
                        }
                    });
                });

            } else {
                log.debug("skip updating delta, resume request");
                request.resume();
                router.route(request);
            }
        });
    }

    private void handleDeltaEtag(final HttpServerRequest request, final Logger log, final Handler<Boolean> callback){
        /*
         * When no Etag is provided we just do the delta update
         */
        if(!request.headers().contains(IF_NONE_MATCH_HEADER)){
            callback.handle(Boolean.TRUE);
            return;
        }

        /*
         * Loading Delta-Etag from storage to compare with header
         */
        final String requestEtag = request.headers().get(IF_NONE_MATCH_HEADER);
        final String etagResourceKey = getResourceKey(request.path(), true);
        redisClient.get(etagResourceKey, event -> {
            if(event.failed()){
                log.error("get command for redisKey {} failed with cause: {}", etagResourceKey, logCause(event));
                callback.handle(Boolean.TRUE);
                return;
            }

            String etagFromStorage = event.result();
            if(StringUtils.isEmpty(etagFromStorage)){
                    /*
                     * No Etag entry found. Store it and then do the delta update
                     */
                saveOrUpdateDeltaEtag(etagResourceKey, request, log, aBoolean -> callback.handle(Boolean.TRUE));
            } else {
                    /*
                     * If etags match, no delta update has to be made.
                     * If not, store/update the etag and then update the delta
                     */
                if(etagFromStorage.equals(requestEtag)){
                    callback.handle(Boolean.FALSE);
                } else {
                    saveOrUpdateDeltaEtag(etagResourceKey, request, log, aBoolean -> callback.handle(Boolean.TRUE));
                }
            }
        });
    }

    private void saveOrUpdateDeltaEtag(final String etagResourceKey, final HttpServerRequest request, final Logger log, final Handler<Boolean> updateCallback){
        final String requestEtag = request.headers().get(IF_NONE_MATCH_HEADER);
        Long expireAfter = getExpireAfterValue(request, log);
        saveDelta(etagResourceKey, requestEtag, expireAfter, event ->{
            if(event.failed()){
                log.error("setex command for redisKey {} failed with cause: {}", etagResourceKey, logCause(event));
            }
            updateCallback.handle(Boolean.TRUE);
        });
    }

    private void saveDelta(String deltaKey, String deltaValue, Long expireAfter, Handler<AsyncResult<Object>> handler) {
        if (expireAfter == null) {
            redisClient.set(deltaKey, deltaValue, (Handler) handler);
        } else {
            redisClient.setex(deltaKey, expireAfter, deltaValue, (Handler) handler);
        }
    }

    private String extractStringDeltaParameter(HttpServerRequest request, Logger log) {
	    String updateIdValue = request.params().get(DELTA_PARAM);
	    if(updateIdValue == null) {
            respondInvalidDeltaParameter(updateIdValue, request, log);
	        return null;
	    } else {
	    	return updateIdValue;
	    }
    }
    
    private Long extractNumberDeltaParameter(String deltaStringId, HttpServerRequest request, Logger log) {
        try {
            return Long.parseLong(deltaStringId);
        } catch (Exception exception) {
            respondInvalidDeltaParameter(deltaStringId, request, log);
            return null;
        }
    }

    private void respondInvalidDeltaParameter(String deltaStringId, HttpServerRequest request, Logger log){
        request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        request.response().setStatusMessage("Invalid delta parameter");
        request.response().end(request.response().getStatusMessage());
        log.error("Bad Request: {} '{}'", request.response().getStatusMessage(), deltaStringId);
    }

    private DeltaResourcesContainer getDeltaResourceNames(List<String> subResourceNames, JsonArray storageUpdateIds, long updateId) {
        List<String> deltaResourceNames = new ArrayList<>();
        long maxUpdateId = 0;

        for (int i = 0; i < storageUpdateIds.size(); i++) {
            try {
                Long storedUpdateId = Long.parseLong(storageUpdateIds.getString(i));
                if (storedUpdateId > updateId) {
                    deltaResourceNames.add(subResourceNames.get(i));
                }
                if (storedUpdateId > maxUpdateId) {
                    maxUpdateId = storedUpdateId;
                }
            } catch (NumberFormatException ex) {
                // No error. Just a resource with no update-in in storage
                deltaResourceNames.add(subResourceNames.get(i));
            }
        }

        return new DeltaResourcesContainer(maxUpdateId, deltaResourceNames);
    }

    private void handleCollectionGET(final HttpServerRequest request, final String updateId, final Logger log) {
        request.pause();

        final HttpMethod method = HttpMethod.GET;
        final String targetUri = ExpansionDeltaUtil.constructRequestUri(request.path(), request.params(), null, null, SlashHandling.KEEP);
        log.debug("constructed uri for request: {}", targetUri);

        final HttpClientRequest cReq = httpClient.request(method, targetUri, cRes -> {
            HttpServerRequestUtil.prepareResponse(request, cRes);

            if(cRes.headers().contains(DELTA_HEADER)) {
                cRes.handler(data -> request.response().write(data));
                cRes.endHandler(v -> request.response().end());
            } else {
                cRes.bodyHandler(data -> {
                    try {
                        Set<String> originalParams = null;
                        if(request.params() != null){
                            originalParams = request.params().names();
                        }
                        final CollectionResourceContainer dataContainer = ExpansionDeltaUtil.verifyCollectionResponse(request, data, originalParams);
                        final List<String> subResourceNames = dataContainer.getResourceNames();
                        final List<String> deltaResourceKeys = buildDeltaResourceKeys(request.path(), subResourceNames);

                        final long updateIdNumber = extractNumberDeltaParameter(updateId, request, log);

                        if(log.isTraceEnabled())  {
                            log.trace("DeltaHandler: deltaResourceKeys for targetUri ({}): {}", targetUri, deltaResourceKeys.toString());
                        }

                        if(deltaResourceKeys.size() > 0) {
                            if(log.isTraceEnabled())  {
                                log.trace("DeltaHandler: targetUri ({}) using mget command.", targetUri);
                            }

                            // read update-ids
                            redisClient.mgetMany(deltaResourceKeys, event -> {
                                if(event.failed()){
                                    log.error("mget command failed with cuase: {}", logCause(event));
                                    handleError(request, "error reading delta information");
                                    return;
                                }
                                JsonArray mgetValues = event.result();
                                DeltaResourcesContainer deltaResourcesContainer = getDeltaResourceNames(subResourceNames, mgetValues, updateIdNumber);

                                JsonObject result = buildResultJsonObject(deltaResourcesContainer.getResourceNames(), dataContainer.getCollectionName());
                                request.response().putHeader(DELTA_HEADER, "" + deltaResourcesContainer.getMaxUpdateId());
                                request.response().end(result.toString());
                            });

                        } else {
                            if(log.isTraceEnabled())  {
                                log.trace("DeltaHandler: targetUri ({}) NOT using database", targetUri);
                            }
                            request.response().putHeader(DELTA_HEADER, "" + updateIdNumber);
                            request.response().end(data);
                        }
                    } catch (ResourceCollectionException exception) {
                        final HttpServerResponse response = request.response();
                        if (StatusCode.NOT_FOUND.equals(exception.getStatusCode())) {
                            log.info("Failed to handle get for collection because collection could not be found");
                        } else {
                            log.error("Failed to handle get for collection", exception);
                        }
                        response.setStatusCode(exception.getStatusCode().getStatusCode());
                        response.setStatusMessage(exception.getStatusCode().getStatusMessage());
                        response.putHeader("Content-Type", "text/plain");
                        if (StatusCode.BAD_GATEWAY.equals(exception.getStatusCode())) {
                            response.write("Failed to handle upstream response for \"" + method.name() + " " + targetUri + "\".\nCAUSED BY: ");
                        }
                        response.end(exception.getMessage());
                    }
                });
            }
            cRes.exceptionHandler(ExpansionDeltaUtil.createResponseExceptionHandler(request, targetUri, DeltaHandler.class));
        });
        cReq.setTimeout(TIMEOUT);
        cReq.headers().setAll(request.headers());
        // add a marker header to signalize, that in the next loop of the mainverticle we should pass the deltahandler
        cReq.headers().set(DELTA_BACKEND_HEADER, "");
        cReq.headers().set("Accept", "application/json");
        cReq.setChunked(true);
        request.handler(cReq::write);
        request.endHandler(v -> {
            cReq.end();
            log.debug("Request done. Request : {}", cReq);
        });
        cReq.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(request, targetUri, DeltaHandler.class));
        request.resume();
    }

    private List<String> buildDeltaResourceKeys(String requestPath, List<String> subResourceNames) {
        List<String> storageResourceKeys = new ArrayList<>();
        String resourceKeyPrefix = getResourceKey(requestPath, false);
        for (String entry : subResourceNames) {
            storageResourceKeys.add(resourceKeyPrefix + ":" + entry);
        }
        return storageResourceKeys;
    }

    private JsonObject buildResultJsonObject(List<String> subResourceNames, String collectionName) {
        JsonArray arr = new JsonArray();
        subResourceNames.forEach(arr::add);
        JsonObject result = new JsonObject();
        result.put(collectionName, arr);
        return result;
    }

    private void handleError(HttpServerRequest request, String errorMessage) {
        request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
        request.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
        request.response().end(errorMessage);
    }

    private String getResourceKey(String path, boolean useEtagPrefix) {
        List<String> pathSegments = Lists.newArrayList(Splitter.on(SLASH).omitEmptyStrings().split(path));
        if(useEtagPrefix){
            pathSegments.add(0, ETAG_KEY_PREFIX);
        } else {
            pathSegments.add(0, RESOURCE_KEY_PREFIX);
        }
        return Joiner.on(":").skipNulls().join(pathSegments);
    }

    private Long getExpireAfterValue(HttpServerRequest request, Logger log) {
        MultiMap requestHeaders = request.headers();
        String expireAfterHeaderValue = requestHeaders.get(EXPIRE_AFTER_HEADER);
        if (expireAfterHeaderValue == null) {
            log.debug("Setting NO expiry on delta key because header {} not defined", EXPIRE_AFTER_HEADER);
            return null; // no expiry
        }

        try {
            long value = Long.parseLong(expireAfterHeaderValue);

            // redis returns an error if setex is called with negativ values
            if (value < 0) {
                log.warn("Setting NO expiry on delta key because because defined value for header {} is a negative number: {}",
                        EXPIRE_AFTER_HEADER, expireAfterHeaderValue);
                return null; // no expiry
            }
            log.debug("Setting expiry on delta key to {} seconds as defined in header {}", value, EXPIRE_AFTER_HEADER);
            return value;
        } catch (Exception e) {
            log.warn("Setting NO expiry on delta key because header {} is not a number: {}", EXPIRE_AFTER_HEADER, expireAfterHeaderValue);
            return null; // default: no expiry
        }
    }

    private class DeltaResourcesContainer {
        private final long maxUpdateId;
        private final List<String> resourceNames;

        public DeltaResourcesContainer(long maxUpdateId, List<String> resourceNames) {
            this.maxUpdateId = maxUpdateId;
            this.resourceNames = resourceNames;
        }

        public long getMaxUpdateId() {
            return maxUpdateId;
        }

        public List<String> getResourceNames() {
            return resourceNames;
        }
    }

    private String logCause(AsyncResult result){
        if(result.cause() != null){
            return result.cause().getMessage();
        }
        return null;
    }
}
