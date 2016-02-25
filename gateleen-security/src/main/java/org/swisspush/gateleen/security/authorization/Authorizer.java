package org.swisspush.gateleen.security.authorization;

import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.RoleExtractor;
import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Authorizer {

    private static final String UPDATE_ADDRESS = "gateleen.authorization-updated";

    private Pattern userUriPattern;

    private String aclKey = "acls";

    private String aclRoot;

    private String adminRole = "admin";
    private String anonymousRole = "everyone";

    private String deviceHeader = "x-rp-deviceid";
    private String userHeader = "x-rp-usr";

    private PatternHolder aclUriPattern;

    private Vertx vertx;
    private EventBus eb;

    private ResourceStorage storage;

    private RoleExtractor roleExtractor;

    private Map<PatternHolder, Map<String, Set<String>>> initialGrantedRoles;

    // URI -> Method -> Roles
    private Map<PatternHolder, Map<String, Set<String>>> grantedRoles = new HashMap<>();

    public static final Logger log = LoggerFactory.getLogger(Authorizer.class);

    public Authorizer(Vertx vertx, final ResourceStorage storage, String securityRoot, String rolePattern) {
        this.vertx = vertx;
        this.storage = storage;
        this.aclRoot = securityRoot + aclKey + "/";
        this.aclUriPattern = new PatternHolder(Pattern.compile("^" + aclRoot + "(?<role>.+)$"));
        this.userUriPattern = Pattern.compile(securityRoot + "user(\\?.*)?");
        this.roleExtractor = new RoleExtractor(rolePattern);
        eb = vertx.eventBus();

        initialGrantedRoles = new HashMap<>();
        initialGrantedRoles.put(aclUriPattern, new HashMap<>());
        initialGrantedRoles.get(aclUriPattern).put("PUT", new HashSet<>());
        initialGrantedRoles.get(aclUriPattern).put("GET", new HashSet<>());
        initialGrantedRoles.get(aclUriPattern).put("DELETE", new HashSet<>());
        initialGrantedRoles.get(aclUriPattern).get("PUT").add(adminRole);
        initialGrantedRoles.get(aclUriPattern).get("GET").add(adminRole);
        initialGrantedRoles.get(aclUriPattern).get("DELETE").add(adminRole);

        updateAllAcls();

        // Receive update notifications
        eb.consumer(UPDATE_ADDRESS, (Handler<Message<String>>) role -> updateAllAcls());
    }

    public void authorize(final HttpServerRequest request, final Handler<Void> handler) {

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
                request.response().end(user.toString());
                return;
            } else {
                request.response().setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
                request.response().setStatusMessage(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
                request.response().end();
                return;
            }
        }

        if (!isAuthorized(request)) {
            RequestLoggerFactory.getLogger(Authorizer.class, request).info("403 Forbidden");
            request.response().setStatusCode(StatusCode.FORBIDDEN.getStatusCode());
            request.response().setStatusMessage(StatusCode.FORBIDDEN.getStatusMessage());
            request.response().end(StatusCode.FORBIDDEN.getStatusMessage());
            return;
        }

        // Intercept configuration
        final Matcher matcher = aclUriPattern.getPattern().matcher(request.uri());
        if (matcher.matches()) {
            if (HttpMethod.PUT == request.method()) {
                request.bodyHandler(buffer -> {
                    try {
                        parseAcl(buffer);
                    } catch (IllegalArgumentException | DecodeException e) {
                        request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                        request.response().setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
                        request.response().end(e.getMessage());
                        return;
                    }
                    storage.put(request.uri(), buffer, status -> {
                        if (status == StatusCode.OK.getStatusCode()) {
                            scheduleUpdate();
                        } else {
                            request.response().setStatusCode(status);
                        }
                        request.response().end();
                    });
                });
                return;
            }
            if (HttpMethod.DELETE == request.method()) {
                storage.delete(request.uri(), status -> {
                    if (status == StatusCode.OK.getStatusCode()) {
                        eb.publish(UPDATE_ADDRESS, "*");
                    } else {
                        log.warn("Could not delete '" + (request.uri() == null ? "<null>" : request.uri()) + "'. Error code is '" + (status == null ? "<null>" : status) + "'.");
                        request.response().setStatusCode(status);
                    }
                    request.response().end();
                });
                return;
            }
        }

        handler.handle(null);
    }

    private long updateTimerId = -1;

    private void scheduleUpdate() {
        vertx.cancelTimer(updateTimerId);
        updateTimerId = vertx.setTimer(3000, id -> eb.publish(UPDATE_ADDRESS, "*"));
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

    private boolean isAuthorized(Set<String> roles, HttpServerRequest request) {
        for (Map.Entry<PatternHolder, Map<String, Set<String>>> entry : grantedRoles.entrySet()) {
            Matcher matcher = entry.getKey().getPattern().matcher(request.uri());
            if (matcher.matches()) {
                Set<String> methodRoles = entry.getValue().get(request.method().name());
                if (methodRoles != null) {
                    for (String role : methodRoles) {
                        if (checkRole(roles, request, matcher, role)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

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
                } catch (IllegalArgumentException e) {
                    log.error("Could not reconfigure routing", e);
                }
            } else {
                log.error("No acl for role " + role + " found in storage");
            }
        });
    }

    private Map<PatternHolder, Set<String>> parseAcl(Buffer buffer) {
        Map<PatternHolder, Set<String>> result = new HashMap<>();

        JsonObject aclItems = new JsonObject(buffer.toString("UTF-8"));

        for (String id : aclItems.fieldNames()) {
            Object aclItemToTest = aclItems.getValue(id);
            if (!(aclItemToTest instanceof JsonObject)) {
                throw new IllegalArgumentException("acl item must be a map: " + id);
            }
            JsonObject aclItem = aclItems.getJsonObject(id);
            aclItems.getValue("debug.read");
            String path = aclItem.getString("path");
            JsonArray methods = aclItem.getJsonArray("methods");
            checkPropertiesValid(path, methods, id);
            if (path != null) {
                PatternHolder holder = new PatternHolder(Pattern.compile(path));
                Set<String> methodSet = result.get(holder);
                if (methodSet == null) {
                    methodSet = new HashSet<>();
                    result.put(holder, methodSet);
                }
                if (methods != null) {
                    for (Object methodObj : methods) {
                        String method = (String) methodObj;
                        methodSet.add(method);
                    }
                }
            }
        }
        return result;
    }

    private void checkPropertiesValid(String path, JsonArray methods, String id) {
        if (path == null && methods != null) {
            throw new IllegalArgumentException("Missing path for defined method list permission " + id);
        }
    }

    private void mergeAcl(String role, Buffer buffer) {
        Map<PatternHolder, Set<String>> permissions = parseAcl(buffer);
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
