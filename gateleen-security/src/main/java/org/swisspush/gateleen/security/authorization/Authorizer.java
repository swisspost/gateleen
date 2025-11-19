package org.swisspush.gateleen.security.authorization;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.event.TrackableEventPublish;
import org.swisspush.gateleen.core.http.UriBuilder;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.RoleExtractor;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.security.PatternHolder;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Authorizer implements LoggableResource {

    private static final String UPDATE_ADDRESS = "gateleen.authorization-updated";
    private final Pattern userUriPattern;

    private final String aclKey = "acls";

    private final String anonymousRole = "everyone";

    private final RoleMapper roleMapper;
    private final RoleAuthorizer roleAuthorizer;

    private final PatternHolder aclUriPattern;
    private final PatternHolder roleMapperUriPattern;

    private final Vertx vertx;
    private final EventBus eb;

    private boolean logACLChanges = false;
    private final ResourceStorage storage;
    private final RoleExtractor roleExtractor;
    private final TrackableEventPublish trackableEventPublish;

    public static final Logger log = LoggerFactory.getLogger(Authorizer.class);

    /*
     * Constructor for backward compatibility without rolePrefix and properties
     */
    public Authorizer(Vertx vertx, final ResourceStorage storage, String securityRoot, String rolePattern) {
        this(vertx, storage, securityRoot, rolePattern, null, null);
    }

    /*
     * Initializes the ACL security system with the RoleMapper from the corresponding storage resources containing the
     * ACL groups (without the role prefix) and the RoleMapper resource. Requests without roles will be granted!
     */
    public Authorizer(Vertx vertx, final ResourceStorage storage, String securityRoot, String rolePattern,
                      String rolePrefix, Map<String, Object> properties) {
        this(vertx, storage, securityRoot, rolePattern, rolePrefix, properties, true);
    }

    /*
     * Initializes the ACL security system with the RoleMapper from the corresponding storage resources containing the
     * ACL groups (without the role prefix) and the RoleMapper resource. Requests without roles can be granted or rejected
     */
    public Authorizer(Vertx vertx, final ResourceStorage storage, String securityRoot, String rolePattern,
                      String rolePrefix, Map<String, Object> properties, boolean grantAccessWithoutRoles) {
        this.vertx = vertx;
        this.storage = storage;
        String aclRoot = UriBuilder.concatUriSegments(securityRoot, aclKey, "/");
        this.aclUriPattern = new PatternHolder("^" + aclRoot + "(?<role>.+)$");
        this.userUriPattern = Pattern.compile(securityRoot + "user(\\?.*)?");
        this.roleMapperUriPattern = new PatternHolder("^" + UriBuilder.concatUriSegments(securityRoot, RoleMapper.ROLEMAPPER));
        this.roleExtractor = new RoleExtractor(rolePattern);
        this.roleMapper = new RoleMapper(storage, securityRoot, properties);
        this.roleAuthorizer = new RoleAuthorizer(storage, securityRoot, rolePattern, rolePrefix, roleMapper, grantAccessWithoutRoles);
        this.trackableEventPublish = new TrackableEventPublish(vertx);
        eb = vertx.eventBus();

        // Receive update notifications
        trackableEventPublish.consumer(vertx, UPDATE_ADDRESS, event -> updateAllConfigs());

    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logACLChanges = resourceLoggingEnabled;
    }


    public Future<Boolean> authorize(final HttpServerRequest request) {
        Promise<Boolean> promise = Promise.promise();

        handleUserUriRequest(request, promise);

        if (!promise.future().isComplete()) {
            roleAuthorizer.handleIsAuthorized(request, promise);
        }

        if (!promise.future().isComplete()) {
            handleConfigurationUriRequest(request, promise, aclUriPattern, roleAuthorizer);
        }

        if (!promise.future().isComplete()) {
            handleConfigurationUriRequest(request, promise, roleMapperUriPattern, roleMapper);
        }

        if (!promise.future().isComplete()) {
            promise.complete(Boolean.TRUE);
        }

        return promise.future();
    }

    public void authorize(final HttpServerRequest request, final Handler<Void> handler) {
        Promise<Boolean> promise = Promise.promise();

        handleUserUriRequest(request, promise);

        if (!promise.future().isComplete()) {
            roleAuthorizer.handleIsAuthorized(request, promise);
        }

        if (!promise.future().isComplete()) {
            handleConfigurationUriRequest(request, promise, aclUriPattern, roleAuthorizer);
        }

        if (!promise.future().isComplete()) {
            handleConfigurationUriRequest(request, promise, roleMapperUriPattern, roleMapper);
        }

        if (!promise.future().isComplete()) {
            handler.handle(null);
        }
    }

    private void handleUserUriRequest(final HttpServerRequest request, Promise<Boolean> promise) {
        if (userUriPattern.matcher(request.uri()).matches()) {
            if (HttpMethod.GET == request.method()) {
                String userId = request.headers().get("x-rp-usr");
                JsonObject user = new JsonObject();
                request.response().headers().set("Content-Type", "application/json");
                String userName = request.headers().get("cas_name");
                if (userName != null) {
                    userId = userName;
                }
                user.put("userId", userId);
                Set<String> roles = roleExtractor.extractRoles(request);
                if (roles != null) {
                    roles.add(anonymousRole);
                    user.put("roles", new JsonArray(new ArrayList<>(roles)));
                }
                ResponseStatusCodeLogUtil.info(request, StatusCode.OK, Authorizer.class);
                request.response().end(user.toString());
            } else {
                ResponseStatusCodeLogUtil.info(request, StatusCode.METHOD_NOT_ALLOWED, Authorizer.class);
                request.response().setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
                request.response().setStatusMessage(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
                request.response().end();
            }
            promise.complete(Boolean.FALSE);
        }
    }

    /**
     * Common handler for uri requests with PatternHolder and a class implementing the AuthorisationResource Interface
     *
     * @param request       The original request
     * @param promise       The promise with the result feeded
     * @param patternHolder The pattern with the Configuration Resource to be used for Configuration reload
     * @param checker       The checker Object (implementing ConfigurationResource interface) to be used to validate the configuration
     */
    private void handleConfigurationUriRequest(final HttpServerRequest request, Promise<Boolean> promise, PatternHolder patternHolder, ConfigurationResource checker) {
        // Intercept configuration
        final Matcher aclMatcher = patternHolder.getPattern(request.headers()).matcher(request.uri());
        if (aclMatcher.matches()) {
            if (HttpMethod.PUT == request.method()) {
                request.bodyHandler(buffer -> {
                    try {
                        checker.checkConfigResource(buffer);
                    } catch (ValidationException validationException) {
                        log.warn("Could not parse acl: {}", validationException.toString());
                        ResponseStatusCodeLogUtil.info(request, StatusCode.BAD_REQUEST, Authorizer.class);
                        request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                        request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage() + " " + validationException.getMessage());
                        if (validationException.getValidationDetails() != null) {
                            request.response().headers().add("content-type", "application/json");
                            request.response().end(validationException.getValidationDetails().encode());
                        } else {
                            request.response().end(validationException.getMessage());
                        }
                        return;
                    }
                    storage.put(request.uri(), buffer, status -> {
                        if (status == StatusCode.OK.getStatusCode()) {
                            if (logACLChanges) {
                                RequestLogger.logRequest(vertx.eventBus(), request, status, buffer);
                            }
                            scheduleUpdate();
                        } else {
                            request.response().setStatusCode(status);
                        }
                        ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), Authorizer.class);
                        request.response().end();
                    });
                });
                promise.complete(Boolean.FALSE);
            } else if (HttpMethod.DELETE == request.method()) {
                storage.delete(request.uri(), status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        trackableEventPublish.publish(vertx, UPDATE_ADDRESS, true);
                    } else {
                        log.warn("Could not delete '{}'. Error code is '{}'.", (request.uri() == null ? "<null>" : request.uri()),
                                (status == null ? "<null>" : status));
                        request.response().setStatusCode(status);
                    }
                    ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), Authorizer.class);
                    request.response().end();
                });
                promise.complete(Boolean.FALSE);
            }
        }
    }


    private void updateAllConfigs() {
        roleAuthorizer.configUpdate();
        roleMapper.configUpdate();
    }

    private long updateTimerId = -1;

    private void scheduleUpdate() {
        vertx.cancelTimer(updateTimerId);
        updateTimerId = vertx.setTimer(3000, id -> trackableEventPublish.publish(vertx, UPDATE_ADDRESS, true));
    }

}
