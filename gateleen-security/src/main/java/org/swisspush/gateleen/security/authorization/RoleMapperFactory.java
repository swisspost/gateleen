package org.swisspush.gateleen.security.authorization;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.core.validation.ValidationResult;
import org.swisspush.gateleen.validation.ValidationException;
import org.swisspush.gateleen.validation.Validator;

import java.util.*;
import java.util.regex.Pattern;

/**
 * RoleMapperFactory is used to parse RoleMapper resources.
 *
 */
public class RoleMapperFactory {

    private String mapperSchema;

    private Logger log = LoggerFactory.getLogger(RoleMapperFactory.class);

    public RoleMapperFactory() {
        this.mapperSchema = ResourcesUtils.loadResource("gateleen_security_schema_rolemapper", true);
    }


    public List<RoleMapperHolder> parseRoleMapper(Buffer buffer) throws ValidationException {
        ValidationResult validationResult = Validator.validateStatic(buffer, mapperSchema, log);
        if (!validationResult.isSuccess()) {
           throw new ValidationException(validationResult);
        }

        List<RoleMapperHolder> result = new ArrayList<>();
        JsonObject mapItems = new JsonObject(buffer.toString("UTF-8"));
        JsonArray mappers = mapItems.getJsonArray("mappings");
        for (Object obj : mappers) {
            JsonObject mapper = new JsonObject((obj.toString()));
            String pattern = mapper.getString("pattern");
            String role = mapper.getString("role");
            Boolean keepOriginal = mapper.getBoolean("keepOriginal");
            if (StringUtils.isNotEmptyTrimmed(pattern) && StringUtils.isNotEmptyTrimmed(role) && keepOriginal!=null) {
                result.add(new RoleMapperHolder(Pattern.compile(pattern), role, keepOriginal));
            }
        }
        return result;
    }


}
