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
     * mapped roles including the initial list of roles according to the given mapping rule sets.
     *
     * @param roles The roles to be mapped and enriched according to the rolemapper object
     * @return The resulting list of initial plus mapped roles
     */
    public Set<String> mapRoles(Set<String> roles) {
        if (roles != null && roleMappers != null && !roleMappers.isEmpty()) {
            Set<String> mappedRoles = new HashSet<>();
            String originalRole = null; // holds the last original role to be applied in  mapping rule chains
            Matcher matcher;
            for (String role : roles) {
                originalRole = role;
                for (RoleMapperHolder mapper : roleMappers) {
                    matcher = mapper.getPattern().matcher(role);
                    if (matcher.matches()) {
                        // we found a matching mapping rule to map and therefore,
                        // we must replace matching regex capture groups if there are any
                        String matchedRole = matcher.replaceAll(mapper.getRole());
                        // check if we must use the kept last original role for this mapper rule
                        if (mapper.getKeepOriginal()) {
                                mappedRoles.add(originalRole);
                        }
                        // now we check if we have to continue matching in the chain of RoleMapper definitions
                        if (mapper.getContinueMapping()) {
                            // go on with next mapping rule, but the new original rule from now on is the one
                            // which matched here and not the previous original one
                            originalRole = matchedRole;
                        } else {
                            originalRole=null;
                            mappedRoles.add(matchedRole);
                            // we don't have to loop further as it is finally mapped now for this given role
                            // according to the mapping definition
                            break;
                        }
                    }
                }
                // Finally add what is the last known OriginalRole up to here if there is any
                if (originalRole!=null) {
                    mappedRoles.add(originalRole);
                }
            }
            return mappedRoles;
        }
        return roles;
    }

}
