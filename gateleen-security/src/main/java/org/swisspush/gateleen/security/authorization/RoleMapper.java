package org.swisspush.gateleen.security.authorization;

import io.vertx.core.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.UriBuilder;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.*;

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
            boolean keepOriginalRule;
            for (String role : roles) {
                keepOriginalRule = true;
                for (RoleMapperHolder mapper : roleMappers) {
                    if (mapper.getPattern().matcher(role).matches()) {
                        // we found a matching rule to map, add it to the resulting list of roles
                        mappedRoles.add(mapper.getRole());
                        // check if we must keep the original rule as well in this case
                        keepOriginalRule = mapper.getKeepOriginal();
                        // we don't have to loop further as it is mapped now for this given role
                        break;
                    }
                }
                if (keepOriginalRule) {
                    mappedRoles.add(role);
                }
            }
            return mappedRoles;
        }
        return roles;
    }

}
