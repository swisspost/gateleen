package org.swisspush.gateleen.delta;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.*;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.CollectionResourceContainer;
import org.swisspush.gateleen.core.util.ExpansionDeltaUtil.SlashHandling;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingHandler;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.routing.Router;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleFeaturesProvider;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.*;

import static org.swisspush.gateleen.logging.LoggingHandler.SKIP_LOGGING_HEADER;
import static org.swisspush.gateleen.routing.RuleFeatures.Feature.DELTA_ON_BACKEND;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DeltaHandler implements RuleProvider.RuleChangesObserver {

    private Logger log = LoggerFactory.getLogger(DeltaHandler.class);

    private static final String DELTA_PARAM = "delta";
    private static final String LIMIT_PARAM = "limit";
    private static final String OFFSET_PARAM = "offset";
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
    private RedisProvider redisProvider;

    private boolean rejectLimitOffsetRequests;

    private RuleProvider ruleProvider;

    private Vertx vertx;
    private LoggingResourceManager loggingResourceManager;
    private LogAppenderRepository logAppenderRepository;
    private RuleFeaturesProvider ruleFeaturesProvider = new RuleFeaturesProvider(new ArrayList<>());

    public DeltaHandler(Vertx vertx, RedisProvider redisProvider, HttpClient httpClient, RuleProvider ruleProvider,
                        LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository) {
        this(vertx,redisProvider, httpClient, ruleProvider, loggingResourceManager, logAppenderRepository, false);
    }

    public DeltaHandler(Vertx vertx, RedisProvider redisProvider, HttpClient httpClient, RuleProvider ruleProvider,
                        LoggingResourceManager loggingResourceManager, LogAppenderRepository logAppenderRepository,
                        boolean rejectLimitOffsetRequests) {
        this.vertx = vertx;
        this.redisProvider = redisProvider;
        this.httpClient = httpClient;
        this.rejectLimitOffsetRequests = rejectLimitOffsetRequests;

        this.ruleProvider = ruleProvider;
        this.loggingResourceManager = loggingResourceManager;
        this.logAppenderRepository = logAppenderRepository;
        this.ruleProvider.registerObserver(this);
    }

    @Override
    public void rulesChanged(List<Rule> rules) {
        log.info("Update deltaOnBackend information from changed routing rules");
        ruleFeaturesProvider = new RuleFeaturesProvider(rules);
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
        if (HttpMethod.GET == request.method() &&
                request.params().contains(DELTA_PARAM) &&
                !request.headers().contains(DELTA_BACKEND_HEADER) &&
                !isBackendDelta(request.uri())) {
            return true;
        }
        // remove the delta backend header, its only a marker
        if (request.headers().contains(DELTA_BACKEND_HEADER)) {
            request.headers().remove(DELTA_BACKEND_HEADER);
        }
        return false;
    }

    private boolean isBackendDelta(String uri) {
        return ruleFeaturesProvider.isFeatureRequest(DELTA_ON_BACKEND, uri);
    }

    public void handle(final HttpServerRequest request, Router router) {
        Logger log = RequestLoggerFactory.getLogger(DeltaHandler.class, request);
        if (isDeltaPUTRequest(request)) {
            handleResourcePUT(request, router, log);
        }
        if (isDeltaGETRequest(request)) {
            String updateId = extractStringDeltaParameter(request, log);
            if (updateId != null) {
                if (rejectLimitOffsetRequests(request)) {
                    respondLimitOffsetParameterForbidden(request, log);
                } else {
                    handleCollectionGET(request, updateId, log);
                }
            }
        }
    }

    private void handleResourcePUT(final HttpServerRequest request, final Router router, final Logger log) {
        request.pause(); // pause the request to avoid problems with starting another async request (storage)
        handleDeltaEtag(request, log, updateDelta -> {
            if (updateDelta) {
                // increment and get update-id
                redisProvider.redis().onSuccess(redisAPI -> redisAPI.incr(SEQUENCE_KEY, reply -> {
                    if (reply.failed()) {
                        log.error("incr command for redisKey {} failed with cause: {}", SEQUENCE_KEY, logCause(reply));
                        handleError(request, "error incrementing/accessing sequence for update-id");
                        return;
                    }

                    final String resourceKey = getResourceKey(request.path(), false);
                    Long expireAfter = getExpireAfterValue(request, log);
                    String updateId = String.valueOf(reply.result());

                    // save to storage
                    saveDelta(resourceKey, updateId, expireAfter, event -> {
                        if (event.failed()) {
                            log.error("setex command for redisKey {} failed with cause: {}", resourceKey, logCause(event));
                            handleError(request, "error saving delta information");
                            request.resume();
                        } else {
                            request.resume();
                            router.route(request);
                        }
                    });
                })).onFailure(throwable -> {
                    log.error("Redis: handleResourcePUT failed", throwable);
                    handleError(request, "handleResourcePUT: error incrementing/accessing sequence for update-id ");
                });
            } else {
                log.debug("skip updating delta, resume request");
                request.resume();
                router.route(request);
            }
        });
    }

    private void handleDeltaEtag(final HttpServerRequest request, final Logger log, final Handler<Boolean> callback) {
        /*
         * When no Etag is provided we just do the delta update
         */
        if (!request.headers().contains(IF_NONE_MATCH_HEADER)) {
            callback.handle(Boolean.TRUE);
            return;
        }

        /*
         * Loading Delta-Etag from storage to compare with header
         */
        final String requestEtag = request.headers().get(IF_NONE_MATCH_HEADER);
        final String etagResourceKey = getResourceKey(request.path(), true);
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.get(etagResourceKey, event -> {
            if (event.failed()) {
                log.error("get command for redisKey {} failed with cause: {}", etagResourceKey, logCause(event));
                callback.handle(Boolean.TRUE);
                return;
            }

            String etagFromStorage = Objects.toString(event.result(), "");
            if (StringUtils.isEmpty(etagFromStorage)) {
                /*
                 * No Etag entry found. Store it and then do the delta update
                 */
                saveOrUpdateDeltaEtag(etagResourceKey, request, log, aBoolean -> callback.handle(Boolean.TRUE));
            } else {
                /*
                 * If etags match, no delta update has to be made.
                 * If not, store/update the etag and then update the delta
                 */
                if (etagFromStorage.equals(requestEtag)) {
                    callback.handle(Boolean.FALSE);
                } else {
                    saveOrUpdateDeltaEtag(etagResourceKey, request, log, aBoolean -> callback.handle(Boolean.TRUE));
                }
            }
        })).onFailure(throwable -> {
            log.error("Redis: handleDeltaEtag failed for redisKey {}", etagResourceKey, throwable);
            callback.handle(Boolean.TRUE);
        });
    }

    private void saveOrUpdateDeltaEtag(final String etagResourceKey, final HttpServerRequest request, final Logger log, final Handler<Boolean> updateCallback) {
        final String requestEtag = request.headers().get(IF_NONE_MATCH_HEADER);
        Long expireAfter = getExpireAfterValue(request, log);
        saveDelta(etagResourceKey, requestEtag, expireAfter, event -> {
            if (event.failed()) {
                log.error("setex command for redisKey {} failed with cause: {}", etagResourceKey, logCause(event));
            }
            updateCallback.handle(Boolean.TRUE);
        });
    }

    private void saveDelta(String deltaKey, String deltaValue, Long expireAfter, Handler<AsyncResult<Object>> handler) {
        redisProvider.redis().onSuccess(redisAPI -> {
            if (expireAfter == null) {
                redisAPI.set(Arrays.asList(deltaKey, deltaValue), (Handler) handler);
            } else {
                redisAPI.setex(deltaKey, String.valueOf(expireAfter), deltaValue, (Handler) handler);
            }
        }).onFailure(throwable -> handler.handle(new FailedAsyncResult<>(throwable)));
    }

    private String extractStringDeltaParameter(HttpServerRequest request, Logger log) {
        String updateIdValue = request.params().get(DELTA_PARAM);
        if (updateIdValue == null) {
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

    private void respondLimitOffsetParameterForbidden(HttpServerRequest request, Logger log) {
        String errorMsg = "limit/offset parameter not allowed for delta requests";
        request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
        request.response().end(errorMsg);
        log.warn(errorMsg);
    }

    private void respondInvalidDeltaParameter(String deltaStringId, HttpServerRequest request, Logger log) {
        request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        request.response().setStatusMessage("Invalid delta parameter");
        request.response().end(request.response().getStatusMessage());
        log.error("Bad Request: {} '{}'", request.response().getStatusMessage(), deltaStringId);
    }

    private DeltaResourcesContainer getDeltaResourceNames(List<String> subResourceNames, Response storageUpdateIds, long updateId) {
        List<String> deltaResourceNames = new ArrayList<>();
        long maxUpdateId = 0;

        for (int i = 0; i < storageUpdateIds.size(); i++) {
            try {
                Long storedUpdateId = Long.parseLong(storageUpdateIds.get(i) != null ? storageUpdateIds.get(i).toString() : null);
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

        final LoggingHandler loggingHandler = new LoggingHandler(loggingResourceManager, logAppenderRepository, request, vertx.eventBus());
        loggingHandler.request(request.headers());

        final HttpMethod method = HttpMethod.GET;
        final String targetUri = ExpansionDeltaUtil.constructRequestUri(request.path(), request.params(), null, null, SlashHandling.KEEP);
        log.debug("constructed uri for request: {}", targetUri);

        httpClient.request(method, targetUri).onComplete(asyncResult -> {
            if (asyncResult.failed()) {
                log.warn("Failed request to {}: {}", targetUri, asyncResult.cause());
                return;
            }
            HttpClientRequest cReq = asyncResult.result();

            cReq.idleTimeout(TIMEOUT);
            cReq.headers().setAll(request.headers());
            // add a marker header to signalize, that in the next loop of the mainverticle we should pass the deltahandler
            cReq.headers().set(DELTA_BACKEND_HEADER, "");
            cReq.headers().set(SKIP_LOGGING_HEADER, "true");
            cReq.headers().set("Accept", "application/json");
            cReq.setChunked(true);
            request.handler(cReq::write);
            request.endHandler(v -> {
                cReq.send(asyncResult1 -> {
                    HttpClientResponse cRes = asyncResult1.result();
                    HttpServerRequestUtil.prepareResponse(request, cRes);

                    loggingHandler.setResponse(cRes);

                    if (cRes.headers().contains(DELTA_HEADER)) {
                        cRes.handler(data -> request.response().write(data));
                        cRes.endHandler(v1 -> request.response().end());
                    } else {
                        cRes.bodyHandler(data -> {
                            try {
                                Set<String> originalParams = null;
                                if (request.params() != null) {
                                    originalParams = request.params().names();
                                }
                                final CollectionResourceContainer dataContainer = ExpansionDeltaUtil.verifyCollectionResponse(request, data, originalParams);
                                final List<String> subResourceNames = dataContainer.getResourceNames();
                                final List<String> deltaResourceKeys = buildDeltaResourceKeys(request.path(), subResourceNames);

                                final long updateIdNumber = extractNumberDeltaParameter(updateId, request, log);

                                if (log.isTraceEnabled()) {
                                    log.trace("DeltaHandler: deltaResourceKeys for targetUri ({}): {}", targetUri, deltaResourceKeys);
                                }

                                if (deltaResourceKeys.size() > 0) {
                                    if (log.isTraceEnabled()) {
                                        log.trace("DeltaHandler: targetUri ({}) using mget command.", targetUri);
                                    }

                                    // read update-ids
                                    redisProvider.redis().onSuccess(redisAPI -> redisAPI.mget(deltaResourceKeys, event -> {
                                        if (event.failed()) {
                                            log.error("mget command failed with cause: {}", logCause(event));
                                            handleError(request, "error reading delta information");
                                            return;
                                        }
                                        Response mgetValues = event.result();
                                        DeltaResourcesContainer deltaResourcesContainer = getDeltaResourceNames(subResourceNames,
                                                mgetValues, updateIdNumber);

                                        JsonObject result = buildResultJsonObject(deltaResourcesContainer.getResourceNames(),
                                                dataContainer.getCollectionName());
                                        String responseBody = result.toString();
                                        request.response().putHeader(DELTA_HEADER,
                                                "" + deltaResourcesContainer.getMaxUpdateId());
                                        loggingHandler.appendResponsePayload(Buffer.buffer(responseBody));
                                        loggingHandler.log();
                                        request.response().end(responseBody);
                                    })).onFailure(event -> {
                                        log.error("Redis: handleCollectionGET failed", event);
                                        handleError(request, "error reading delta information");
                                    });
                                } else {
                                    if (log.isTraceEnabled()) {
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
                log.debug("Request done. Request : {}", cReq);
            });
            cReq.exceptionHandler(ExpansionDeltaUtil.createRequestExceptionHandler(request, targetUri, DeltaHandler.class));
            request.resume();
        });
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

    private boolean rejectLimitOffsetRequests(HttpServerRequest request) {
        if (!rejectLimitOffsetRequests) {
            return false;
        }
        return request.params().contains(LIMIT_PARAM) || request.params().contains(OFFSET_PARAM);
    }

    private void handleError(HttpServerRequest request, String errorMessage) {
        request.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
        request.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
        request.response().end(errorMessage);
    }

    private String getResourceKey(String path, boolean useEtagPrefix) {
        List<String> pathSegments = Lists.newArrayList(Splitter.on(SLASH).omitEmptyStrings().split(path));
        if (useEtagPrefix) {
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

    private static class DeltaResourcesContainer {
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

    private String logCause(AsyncResult result) {
        if (result.cause() != null) {
            return result.cause().getMessage();
        }
        return null;
    }
}
