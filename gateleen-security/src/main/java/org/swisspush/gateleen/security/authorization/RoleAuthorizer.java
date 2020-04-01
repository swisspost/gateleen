package org.swisspush.gateleen.security.authorization;

import io.vertx.core.Future;
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
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class RoleAuthorizer implements ConfigurationResource {

    private String aclRoot;
    private String aclKey = "acls";

    private String adminRole = "admin";
    private String anonymousRole = "everyone";
    private String deviceHeader = "x-rp-deviceid";
    private String userHeader = "x-rp-usr";

    private String rolePrefix = "";

    private AclFactory aclFactory;
    private RoleMapper roleMapper;

    private PatternHolder aclUriPattern;

    private ResourceStorage storage;
    private RoleExtractor roleExtractor;
    private Map<PatternHolder, Map<String, Set<String>>> initialGrantedRoles;

    // URI -> Method -> Roles
    private Map<PatternHolder, Map<String, Set<String>>> grantedRoles = new HashMap<>();

    public static final Logger log = LoggerFactory.getLogger(RoleAuthorizer.class);

    /**
     *
     * @param storage
     * @param securityRoot
     * @param rolePattern The regex pattern to extract the roles from the header removing any prefix from them
     * @param rolePrefix The prefix which must be added to mapped roles in the request header which is created
     *                   to forward from the mapped resulting roles. Could be null if none shall be added at all.
     * @param roleMapper
     */
    RoleAuthorizer(final ResourceStorage storage, String securityRoot, String rolePattern, String rolePrefix, final RoleMapper roleMapper) {
        this.storage = storage;
        this.roleMapper = roleMapper;
        this.aclRoot = securityRoot + aclKey + "/";
        this.aclUriPattern = new PatternHolder(Pattern.compile("^" + aclRoot + "(?<role>.+)$"));
        this.roleExtractor = new RoleExtractor(rolePattern);
        this.aclFactory = new AclFactory();
        // keep empty string if there is no prefix given. This way we could just use it later on without having to care about.
        if (rolePrefix!=null) {
            this.rolePrefix = rolePrefix;
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

    public void handleIsAuthorized(final HttpServerRequest request, Future<Boolean> future) {
        if (!isAuthorized(request)) {
            ResponseStatusCodeLogUtil.info(request, StatusCode.FORBIDDEN, RoleAuthorizer.class);
            request.response().setStatusCode(StatusCode.FORBIDDEN.getStatusCode());
            request.response().setStatusMessage(StatusCode.FORBIDDEN.getStatusMessage());
            request.response().end(StatusCode.FORBIDDEN.getStatusMessage());
            future.complete(Boolean.FALSE);
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
        Set<String> mappedRoles = roleMapper.mapRoles(roles);
        for (Entry<PatternHolder, Map<String, Set<String>>> entry : grantedRoles.entrySet()) {
            Matcher matcher = entry.getKey().getPattern().matcher(request.uri());
            if (matcher.matches()) {
                Set<String> methodRoles = entry.getValue().get(request.method().name());
                if (methodRoles != null) {
                    for (String role : methodRoles) {
                        if (checkRole(mappedRoles, request, matcher, role)) {
                            fillInNewRoleHeader(request,mappedRoles);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void fillInNewRoleHeader(HttpServerRequest request,Set<String>roles)
    {
        StringBuffer roleHeader = new StringBuffer();
        for (String role : roles)
        {
            if (roleHeader.length()>0) roleHeader.append(",");
            roleHeader.append(this.rolePrefix).append(role.toLowerCase());
        }
        request.headers().set(RoleExtractor.groupHeader,roleHeader.toString());
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
        boolean authorized = false;
        if (roles.contains(role)) {
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
                    authorized &= (uriRole != null && roles.contains(uriRole));
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
