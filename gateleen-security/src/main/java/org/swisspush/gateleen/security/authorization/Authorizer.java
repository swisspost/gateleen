package org.swisspush.gateleen.security.authorization;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.RoleExtractor;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Authorizer implements LoggableResource {

    private static final String UPDATE_ADDRESS = "gateleen.authorization-updated";

    private Pattern userUriPattern;

    private String aclRoot;
    private String aclKey = "acls";
    private String roleMapper;

    private String adminRole = "admin";
    private String anonymousRole = "everyone";
    private String deviceHeader = "x-rp-deviceid";
    private String userHeader = "x-rp-usr";

    private String aclSchema;
    private String mapperSchema;
    private AclFactory aclFactory;

    private PatternHolder aclUriPattern;
    private PatternHolder roleMapperUriPattern;

    private Vertx vertx;
    private EventBus eb;

    private boolean logACLChanges = false;
    private ResourceStorage storage;
    private RoleExtractor roleExtractor;
    private Map<PatternHolder, Map<String, Set<String>>> initialGrantedRoles;

    // URI -> Method -> Roles
    private Map<PatternHolder, Map<String, Set<String>>> grantedRoles = new HashMap<>();

    private List<RoleMapperHolder> roleMappers = null;

    public static final Logger log = LoggerFactory.getLogger(Authorizer.class);

    public Authorizer(Vertx vertx, final ResourceStorage storage, String securityRoot, String rolePattern) {
        this.vertx = vertx;
        this.storage = storage;
        this.aclRoot = securityRoot + aclKey + "/";
        this.aclUriPattern = new PatternHolder(Pattern.compile("^" + aclRoot + "(?<role>.+)$"));
        this.userUriPattern = Pattern.compile(securityRoot + "user(\\?.*)?");
        this.roleExtractor = new RoleExtractor(rolePattern);

        this.roleMapper = securityRoot + "rolemapper";
        this.roleMapperUriPattern = new PatternHolder(Pattern.compile(roleMapper));

        this.aclSchema = ResourcesUtils.loadResource("gateleen_security_schema_acl", true);
        this.mapperSchema = ResourcesUtils.loadResource("gateleen_security_schema_rolemapper", true);
        this.aclFactory = new AclFactory(aclSchema, mapperSchema);

        eb = vertx.eventBus();

        initialGrantedRoles = new HashMap<>();
        initialGrantedRoles.put(aclUriPattern, new HashMap<>());
        initialGrantedRoles.get(aclUriPattern).put("PUT", new HashSet<>());
        initialGrantedRoles.get(aclUriPattern).put("GET", new HashSet<>());
        initialGrantedRoles.get(aclUriPattern).put("DELETE", new HashSet<>());
        initialGrantedRoles.get(aclUriPattern).get("PUT").add(adminRole);
        initialGrantedRoles.get(aclUriPattern).get("GET").add(adminRole);
        initialGrantedRoles.get(aclUriPattern).get("DELETE").add(adminRole);

        updateAll();

        // Receive update notifications
        eb.consumer(UPDATE_ADDRESS, (Handler<Message<String>>) role -> updateAll());
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logACLChanges = resourceLoggingEnabled;
    }

    public Future<Boolean> authorize(final HttpServerRequest request) {
        Future<Boolean> future = Future.future();

        handleUserUriRequest(request, future);

        if (!future.isComplete()) {
            handleIsAuthorized(request, future);
        }

        if (!future.isComplete()) {
            handleAclUriRequest(request, future);
        }

        if (!future.isComplete()) {
            handleRoleMapperUriRequest(request, future);
        }

        if (!future.isComplete()) {
            future.complete(Boolean.TRUE);
        }

        return future;
    }

    public void authorize(final HttpServerRequest request, final Handler<Void> handler) {
        Future<Boolean> future = Future.future();

        handleUserUriRequest(request, future);

        if (!future.isComplete()) {
            handleIsAuthorized(request, future);
        }

        if (!future.isComplete()) {
            handleAclUriRequest(request, future);
        }

        if (!future.isComplete()) {
            handleRoleMapperUriRequest(request, future);
        }

        if (!future.isComplete()) {
            handler.handle(null);
        }
    }

    private void handleUserUriRequest(final HttpServerRequest request, Future<Boolean> future) {
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
            future.complete(Boolean.FALSE);
        }
    }

    private void handleIsAuthorized(final HttpServerRequest request, Future<Boolean> future) {
        if (!isAuthorized(request)) {
            ResponseStatusCodeLogUtil.info(request, StatusCode.FORBIDDEN, Authorizer.class);
            request.response().setStatusCode(StatusCode.FORBIDDEN.getStatusCode());
            request.response().setStatusMessage(StatusCode.FORBIDDEN.getStatusMessage());
            request.response().end(StatusCode.FORBIDDEN.getStatusMessage());
            future.complete(Boolean.FALSE);
        }
    }

    private void handleAclUriRequest(final HttpServerRequest request, Future<Boolean> future) {
        // Intercept configuration
        final Matcher aclMatcher = aclUriPattern.getPattern().matcher(request.uri());
        if (aclMatcher.matches()) {
            if (HttpMethod.PUT == request.method()) {
                request.bodyHandler(buffer -> {
                    try {
                        aclFactory.parseAcl(buffer);
                    } catch (ValidationException validationException) {
                        log.warn("Could not parse acl: " + validationException.toString());
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
                future.complete(Boolean.FALSE);
            } else if (HttpMethod.DELETE == request.method()) {
                storage.delete(request.uri(), status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        eb.publish(UPDATE_ADDRESS, "*");
                    } else {
                        log.warn("Could not delete '" + (request.uri() == null ? "<null>" : request.uri()) + "'. Error code is '" + (status == null ? "<null>" : status) + "'.");
                        request.response().setStatusCode(status);
                    }
                    ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), Authorizer.class);
                    request.response().end();
                });
                future.complete(Boolean.FALSE);
            }
        }
    }

    private void handleRoleMapperUriRequest(final HttpServerRequest request, Future<Boolean> future) {
        // Intercept configuration
        final Matcher mapperMatcher = roleMapperUriPattern.getPattern().matcher(request.uri());
        if (mapperMatcher.matches()) {
            if (HttpMethod.PUT == request.method()) {
                request.bodyHandler(buffer -> {
                    try {
                        aclFactory.parseRoleMapper(buffer);
                    } catch (ValidationException validationException) {
                        log.warn("Could not parse acl: " + validationException.toString());
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
                future.complete(Boolean.FALSE);
            } else if (HttpMethod.DELETE == request.method()) {
                storage.delete(request.uri(), status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        eb.publish(UPDATE_ADDRESS, "*");
                    } else {
                        log.warn("Could not delete '" + (request.uri() == null ? "<null>" : request.uri()) + "'. Error code is '" + (status == null ? "<null>" : status) + "'.");
                        request.response().setStatusCode(status);
                    }
                    ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), Authorizer.class);
                    request.response().end();
                });
                future.complete(Boolean.FALSE);
            }
        }
    }


    private long updateTimerId = -1;

    private void scheduleUpdate() {
        vertx.cancelTimer(updateTimerId);
        updateTimerId = vertx.setTimer(3000, id -> eb.publish(UPDATE_ADDRESS, "*"));
    }

    private void updateAll() {
        updateAllAcls();
        updateAllRoleMappers();
    }

    private void updateAllAcls() {
        storage.get(aclRoot, buffer -> {
            if (buffer != null) {
                grantedRoles = new HashMap<>();
                for (Object roleObject : new JsonObject(buffer.toString("UTF-8")).getJsonArray(aclKey)) {
                    String role = (String) roleObject;
                    updateAcl(role);
                }
            } else {
                log.warn("No ACLs in storage, using initial authorization.");
                grantedRoles = initialGrantedRoles;
            }
        });
    }

    /**
     * Retrieve the configured RoleMapper from Storage and populate the corresponding List of mappers.
     */
    private void updateAllRoleMappers() {
        storage.get(roleMapper, buffer -> {
            if (buffer != null) {
                try {
                    roleMappers = aclFactory.parseRoleMapper(buffer);
                } catch (ValidationException validationException) {
                    log.error("Could not parse acl for role mapper: " + validationException.toString());
                }
            } else {
                log.info("No RoleMappers found in storage");
                roleMappers = null;
            }
        });
    }


    /**
     * Extracts the Users Roles from the Request and further validates if the user is allowed to access
     * the URL according to the ACL
     *
     * @param request The Incoming HTTP Request
     * @return true if the user is authorized.
     */
    private boolean isAuthorized(HttpServerRequest request) {
        Set<String> roles = roleExtractor.extractRoles(request);
        if (roles != null) {
            roles.add(anonymousRole);
            RequestLoggerFactory.getLogger(Authorizer.class, request).debug("Roles: " + roles);
            return isAuthorized(roles, request);
        } else {
            return true;
        }
    }


    /**
     * Validates that the Request URL is authorised by any of the given Roles. The roles are mapped first
     * against the defined RoleMapper if any.
     *
     * @param roles   The roles used to validate the ACL. Note that the given Roles
     *                are mapped according to the RoleMapper (if any) before applied against the ACL.
     * @param request The incoming HTTP Request
     * @return true if the user is authorized
     */
    private boolean isAuthorized(Set<String> roles, HttpServerRequest request) {
        Set<String> mappedRoles = mapRoles(roles);
        for (Map.Entry<PatternHolder, Map<String, Set<String>>> entry : grantedRoles.entrySet()) {
            Matcher matcher = entry.getKey().getPattern().matcher(request.uri());
            if (matcher.matches()) {
                Set<String> methodRoles = entry.getValue().get(request.method().name());
                if (methodRoles != null) {
                    for (String role : methodRoles) {
                        if (checkRole(mappedRoles, request, matcher, role)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Maps the received roles from http header according the rolemapper rules and return the set of
     * mapped roles including the initial list of roles.
     *
     * @param roles The roles to be mapped and enrichted according to the rolemapper object
     * @return The resulting list of initial plus mapped roles
     */
    private Set<String> mapRoles(Set<String> roles) {
        if (roles != null && roleMappers != null && !roleMappers.isEmpty()) {
            Set<String> mappedRoles = new HashSet<>();
            for (String role : roles) {
                boolean keepOriginalRole = true;
                for (RoleMapperHolder mapper : roleMappers) {
                    if (mapper.getPattern().matcher(role).matches()) {
                        // we found a matching rule to map, add it to the resulting list of roles
                        mappedRoles.add(mapper.getRole());
                        // check if we must keep the original rule as well in this case
                        keepOriginalRole = mapper.getKeepOriginal();
                        // we don't have to loop further as it is mapped now for this given role
                        break;
                    }
                }
                if (keepOriginalRole) {
                    mappedRoles.add(role);
                }
            }
            return mappedRoles;
        }
        return roles;
    }

    /**
     * Checks if the given set of roles of the user from header does match the configured
     * security role in the ACL
     *
     * @param roles   The list of roles of the user as received on the request but already mapped with rolemapper
     * @param request The http request received
     * @param matcher Contains the matching against the request URL / used for further URL param matchings
     * @param role    The ACL rule to check against
     * @return TRUE if the user is authorized according to the role
     */
    private boolean checkRole(Set<String> roles, HttpServerRequest request, Matcher matcher, String role) {
        if (roles.contains(role)) {
            boolean authorized = true;
            if (matcher.groupCount() > 0) {
                try {
                    String uriUser = matcher.group("user");
                    authorized &= (uriUser != null && uriUser.equals(request.headers().get(userHeader)));
                } catch (IllegalArgumentException e) {
                    // ignore
                }
                try {
                    String uriDevice = matcher.group("device");
                    authorized &= (uriDevice != null && uriDevice.equals(request.headers().get(deviceHeader)));
                } catch (IllegalArgumentException e) {
                    // ignore
                }
                try {
                    String uriRole = matcher.group("role");
                    authorized &= (uriRole != null && roles.contains(uriRole));
                } catch (IllegalArgumentException e) {
                    // ignore
                }
            }
            if (authorized) {
                return true;
            }
        }
        return false;
    }

    private void updateAcl(final String role) {
        storage.get(aclRoot + role, buffer -> {
            if (buffer != null) {
                try {
                    log.info("Applying acl for " + role);
                    mergeAcl(role, buffer);
                } catch (ValidationException validationException) {
                    log.error("Could not parse acls: " + validationException.toString());
                }
            } else {
                log.error("No acl for role " + role + " found in storage");
            }
        });
    }

    private void mergeAcl(String role, Buffer buffer) throws ValidationException {
        Map<PatternHolder, Set<String>> permissions = aclFactory.parseAcl(buffer);
        for (Entry<PatternHolder, Set<String>> entry : permissions.entrySet()) {
            PatternHolder holder = entry.getKey();
            Map<String, Set<String>> aclItem = grantedRoles.get(holder);
            if (aclItem == null) {
                aclItem = new HashMap<>();
                grantedRoles.put(holder, aclItem);
            }
            for (String method : entry.getValue()) {
                Set<String> aclMethod = aclItem.get(method);
                if (aclMethod == null) {
                    aclMethod = new HashSet<>();
                    aclItem.put(method, aclMethod);
                }
                aclMethod.add(role);
            }
        }
    }
}
