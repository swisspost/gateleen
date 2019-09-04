package org.swisspush.gateleen.security.authorization;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.validation.ValidationException;
import org.swisspush.gateleen.validation.Validator;

import java.util.*;
import java.util.regex.Pattern;

/**
 * RoleMapperFactory is used to parse RoleMapper resources.
 */
public class RoleMapperFactory {

    private final String mapperSchema;

    private static Logger LOGGER = LoggerFactory.getLogger(RoleMapperFactory.class);

    RoleMapperFactory() {
        this.mapperSchema = ResourcesUtils.loadResource("gateleen_security_schema_rolemapper", true);
    }


    public List<RoleMapperHolder> parseRoleMapper(Buffer buffer) throws ValidationException {
        ValidationResult validationResult = Validator.validateStatic(buffer, mapperSchema, LOGGER);
        if (!validationResult.isSuccess()) {
            throw new ValidationException(validationResult);
        }
        List<RoleMapperHolder> result = new ArrayList<>();
        Mappings mappings = Json.decodeValue(buffer, Mappings.class);
        for (Mapping mapping : mappings.mappings) {
            result.add(new RoleMapperHolder(Pattern.compile(mapping.pattern), mapping.role, mapping.keepOriginal));
        }
        return result;
    }

    private static class Mappings {
        public Mapping[] mappings;
    }

    private static class Mapping {
        public String pattern, role;
        public boolean keepOriginal;
    }


}
