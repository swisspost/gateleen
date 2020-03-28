package org.swisspush.gateleen.security.authorization;

import io.vertx.core.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.UriBuilder;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;
import java.util.regex.Matcher;

/**
 * Holds and maintains the RoleMapper configuration and performs the mapping.
 */
public class RoleMapper implements ConfigurationResource {

    private ResourceStorage storage;

    private String roleMapper;

    private RoleMapperFactory roleMapperFactory;

    private List<RoleMapperHolder> roleMappers = null;

    public static final Logger log = LoggerFactory.getLogger(RoleMapper.class);

    public static final String ROLEMAPPER = "rolemapper";

    /**
     * Holds the list of all configured RoleMappings and executes the mapping
     *
     * @param storage      Reference to the storage to retrieve the RoleMappings from
     * @param securityRoot Base url to retrieve the rolemapper config resource from (no trailing slash expected nor necessary)
     */
    public RoleMapper(ResourceStorage storage, String securityRoot) {
        this.storage = storage;
        this.roleMapper = UriBuilder.concatUriSegments(securityRoot, ROLEMAPPER);
        this.roleMapperFactory = new RoleMapperFactory();

        configUpdate();
    }


    @Override
    public void checkConfigResource(Buffer buffer) throws ValidationException {
        roleMapperFactory.parseRoleMapper(buffer);
    }


    /**
     * Retrieve the configured RoleMapper from Storage and populate the corresponding List of mappers.
     */
    @Override
    public void configUpdate() {
        storage.get(roleMapper, buffer -> {
            if (buffer != null) {
                try {
                    roleMappers = roleMapperFactory.parseRoleMapper(buffer);
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
     * Maps the received roles from http header according the rolemapper rules and return the set of
     * mapped roles including the initial list of roles.
     *
     * @param roles The roles to be mapped and enriched according to the rolemapper object
     * @return The resulting list of initial plus mapped roles
     */
    public Set<String> mapRoles(Set<String> roles) {
        if (roles != null && roleMappers != null && !roleMappers.isEmpty()) {
            Set<String> mappedRoles = new HashSet<>();
            String originalRole = null; // holds the role currently under mapping check in the loop
            String previousMatchedRole = null; // holds the previous matching role when continuation was defined for a mapping rule
            Matcher matcher;
            for (String role : roles) {
                originalRole = role;
                for (RoleMapperHolder mapper : roleMappers) {
                    matcher = mapper.getPattern().matcher(role);
                    if (matcher.matches()) {
                        // we found a matching mapping rule to map
                        // we must replace matching regex capture groups if there are any
                        String matchedRole = matcher.replaceAll(mapper.getRole());
                        if (mapper.getContinueMapping()) {
                            // go on with next mapping rule
                            // but the new original rule from now on is the one which matched here, not the original one
                            previousMatchedRole = matchedRole;
                            originalRole = null;
                        } else {
                            // we don't have to loop further as it is finally mapped now for this given role according to the mapping definition
                            mappedRoles.add(matchedRole);
                            // check if we must keep the original role as well in this case
                            if (mapper.getKeepOriginal()) {
                                // if we have had before a matching rule where the result was preserved for further usage, we use this one instead of the original one
                                if (previousMatchedRole != null) {
                                    originalRole = previousMatchedRole;
                                }
                            } else {
                                // we don't want to to keep the original or previous one at all.
                                originalRole = null;
                            }
                            break;
                        }
                    }
                }
                // either there was nothing matching at all above or there was a flag to preserve the original rule set as given by the algorythm
                if (originalRole != null) {
                    mappedRoles.add(originalRole);
                }
            }
            return mappedRoles;
        }
        return roles;
    }

}
