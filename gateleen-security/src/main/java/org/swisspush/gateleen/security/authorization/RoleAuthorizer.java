package org.swisspush.gateleen.security.authorization;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.RoleExtractor;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.security.PatternHolder;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;


public class RoleAuthorizer implements ConfigurationResource {

    private String aclRoot;
    private String aclKey = "acls";

    private String adminRole = "admin";
    private String anonymousRole = "everyone";
    private String deviceHeader = "x-rp-deviceid";
    private String userHeader = "x-rp-usr";

    private final String rolePrefix;

    private AclFactory aclFactory;
    private RoleMapper roleMapper;

    private PatternHolder aclUriPattern;

    private ResourceStorage storage;
    private RoleExtractor roleExtractor;
    private Map<PatternHolder, Map<String, Set<String>>> initialGrantedRoles;
    private final boolean grantAccessWithoutRoles;

    // URI -> Method -> Roles
    private Map<PatternHolder, Map<String, Set<String>>> grantedRoles = new HashMap<>();

    public static final Logger log = LoggerFactory.getLogger(RoleAuthorizer.class);

    /**
     * @param storage
     * @param securityRoot
     * @param rolePattern  The regex pattern to extract the roles from the header removing any prefix from them
     * @param rolePrefix   The prefix which must be added to mapped roles in the request header which is created
     *                     to forward from the mapped resulting roles. Could be null if none shall be added at all.
     * @param roleMapper
     * @param grantAccessWithoutRoles
     */
    RoleAuthorizer(final ResourceStorage storage, String securityRoot, String rolePattern, String rolePrefix,
                   final RoleMapper roleMapper, boolean grantAccessWithoutRoles) {
        this.storage = storage;
        this.roleMapper = roleMapper;
        this.grantAccessWithoutRoles = grantAccessWithoutRoles;
        this.aclRoot = securityRoot + aclKey + "/";
        this.aclUriPattern = new PatternHolder("^" + aclRoot + "(?<role>.+)$");
        this.roleExtractor = new RoleExtractor(rolePattern);
        this.aclFactory = new AclFactory();
        // keep empty string if there is no prefix given. This way we could just use it later on without having to care about further.
        if (rolePrefix != null) {
            this.rolePrefix = rolePrefix;
        } else {
            this.rolePrefix = "";
        }

        initialGrantedRoles = new HashMap<>();
        initialGrantedRoles.put(aclUriPattern, new HashMap<>());
        initialGrantedRoles.get(aclUriPattern).put("PUT", new HashSet<>());
        initialGrantedRoles.get(aclUriPattern).put("GET", new HashSet<>());
        initialGrantedRoles.get(aclUriPattern).put("DELETE", new HashSet<>());
        initialGrantedRoles.get(aclUriPattern).get("PUT").add(adminRole);
        initialGrantedRoles.get(aclUriPattern).get("GET").add(adminRole);
        initialGrantedRoles.get(aclUriPattern).get("DELETE").add(adminRole);

        configUpdate();
    }

    public void handleIsAuthorized(final HttpServerRequest request, Promise<Boolean> promise) {
        if (!isAuthorized(request)) {
            ResponseStatusCodeLogUtil.info(request, StatusCode.FORBIDDEN, RoleAuthorizer.class);
            request.response().setStatusCode(StatusCode.FORBIDDEN.getStatusCode());
            request.response().setStatusMessage(StatusCode.FORBIDDEN.getStatusMessage());
            request.response().end(StatusCode.FORBIDDEN.getStatusMessage());
            promise.complete(Boolean.FALSE);
        }
    }

    @Override
    public void checkConfigResource(Buffer buffer) throws ValidationException {
        aclFactory.parseAcl(buffer);
    }

    @Override
    public void configUpdate() {
        storage.get(aclRoot, buffer -> {
            if (buffer != null) {
                grantedRoles = new HashMap<>();
                for (Object roleObject : new JsonObject(buffer).getJsonArray(aclKey)) {
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
     * Extracts the Users Roles from the Request and further validates if the user is allowed to access
     * the URL according to the ACL
     *
     * @param request The Incoming HTTP Request
     * @return true if the user is authorized.
     */
    private boolean isAuthorized(HttpServerRequest request) {
        Set<String> roles = roleExtractor.extractRoles(request);
        if (roles != null) {
            //  NOTE: This here adds the role "everyone" and therefore everybody does get this role assigned
            //        independent of what roles were given initially.
            //        Therefore a ACL for the role "everyone" might be created if one would like to assign
            //        common rights for "everyone"
            //        This is as well distributed further in the request header and might be used by other applications behind.
            roles.add(anonymousRole);
            RequestLoggerFactory.getLogger(RoleAuthorizer.class, request).debug("Roles: " + roles);
            return isAuthorized(roles, request);
        } else {
            return grantAccessWithoutRoles;
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
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        for (Entry<PatternHolder, Map<String, Set<String>>> entry : grantedRoles.entrySet()) {
            Matcher matcher = entry.getKey().getPattern(request.headers()).matcher(request.uri());
            if (matcher.matches()) {
                Set<String> methodRoles = entry.getValue().get(request.method().name());
                if (methodRoles != null) {
                    for (String role : methodRoles) {
                        if (checkRole(mappedRoles, request, matcher, role)) {
                            fillInNewRoleHeader(request, mappedRoles);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void fillInNewRoleHeader(HttpServerRequest request, Map<String, RoleMapper.MappedRole> mappedRoles) {
        StringJoiner joiner = new StringJoiner(",");
        for (RoleMapper.MappedRole mappedRole : mappedRoles.values()) {
            if (mappedRole.forward) {
                joiner.add(this.rolePrefix + mappedRole.role.toLowerCase());
            }
        }
        request.headers().set(RoleExtractor.groupHeader, joiner.toString());
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
    private boolean checkRole(Map<String, RoleMapper.MappedRole> roles, HttpServerRequest request, Matcher matcher, String role) {
        boolean authorized = false;
        if (roles.containsKey(role)) {
            authorized = true;
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
                    authorized &= (uriRole != null && roles.containsKey(uriRole));
                } catch (IllegalArgumentException e) {
                    // ignore
                }
            }
        }
        return authorized;
    }

    private void updateAcl(final String role) {
        storage.get(aclRoot + role, buffer -> {
            if (buffer != null) {
                try {
                    log.info("Applying acl for {}", role);
                    mergeAcl(role, buffer);
                } catch (ValidationException validationException) {
                    log.error("Could not parse acls: {}", validationException.toString());
                }
            } else {
                log.error("No acl for role {} found in storage", role);
            }
        });
    }

    private void mergeAcl(String role, Buffer buffer) throws ValidationException {
        Map<PatternHolder, Set<String>> permissions = aclFactory.parseAcl(buffer);
        for (Entry<PatternHolder, Set<String>> entry : permissions.entrySet()) {
            PatternHolder holder = entry.getKey();
            Map<String, Set<String>> aclItem = grantedRoles.computeIfAbsent(holder, k -> new HashMap<>());
            for (String method : entry.getValue()) {
                Set<String> aclMethod = aclItem.computeIfAbsent(method, k -> new HashSet<>());
                aclMethod.add(role);
            }
        }
    }
}
