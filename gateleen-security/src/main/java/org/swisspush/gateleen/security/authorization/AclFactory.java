package org.swisspush.gateleen.security.authorization;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.validation.ValidationException;
import org.swisspush.gateleen.validation.ValidationResult;
import org.swisspush.gateleen.validation.Validator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AclFactory is used to parse ACL (Access Control List) resources.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class AclFactory {

    private String aclSchema;

    private Logger log = LoggerFactory.getLogger(AclFactory.class);

    public AclFactory(String aclSchema) {
        this.aclSchema = aclSchema;
    }

    public Map<PatternHolder, Set<String>> parseAcl(Buffer buffer) throws ValidationException {
        ValidationResult validationResult = Validator.validateStatic(buffer, aclSchema, log);
        if(!validationResult.isSuccess()){
            throw new  ValidationException(validationResult);
        }

        Map<PatternHolder, Set<String>> result = new HashMap<>();
        JsonObject aclItems = new JsonObject(buffer.toString("UTF-8"));

        for (String id : aclItems.fieldNames()) {
            Object aclItemToTest = aclItems.getValue(id);
            if (!(aclItemToTest instanceof JsonObject)) {
                throw new ValidationException("acl item must be a map: " + id);
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

    private void checkPropertiesValid(String path, JsonArray methods, String id) throws ValidationException {
        if (path == null && methods != null) {
            throw new ValidationException("Missing path for defined method list permission " + id);
        }
    }
}
