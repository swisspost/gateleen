package org.swisspush.gateleen.security.content;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StringUtils;
import org.swisspush.gateleen.security.PatternHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses the Content-Type constraint configuration resource and creates a list of {@link ContentTypeConstraint}s
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class ContentTypeConstraintFactory {

    private static final Logger log = LoggerFactory.getLogger(ContentTypeConstraintFactory.class);

    private static final String ALLOWED_TYPES = "allowedTypes";

    /**
     * Parses the provided Content-Type constraint configuration resource and returns a list of {@link ContentTypeConstraint} objects.
     *
     * When the configuration resource contains invalid regex patterns, a warning will be logged and the corresponding
     * {@link ContentTypeConstraint} object will not be included in the returned list.
     *
     * @param constraintResourceBuffer the resource to create the {@link ContentTypeConstraint}s from
     * @return a list of {@link ContentTypeConstraint} objects
     */
    static List<ContentTypeConstraint> create(Buffer constraintResourceBuffer) {
        List<ContentTypeConstraint> constraints = new ArrayList<>();
        JsonObject config = constraintResourceBuffer.toJsonObject();

        for (String urlPattern : config.fieldNames()) {
            try {
                Pattern pattern = Pattern.compile(urlPattern);
                final List<PatternHolder> allowedTypes = extractAllowedTypes(config.getJsonObject(urlPattern));
                if(!allowedTypes.isEmpty()){
                    constraints.add(new ContentTypeConstraint(new PatternHolder(pattern.pattern()), allowedTypes));
                    log.info("Constraint '{}' successfully parsed and added to constraint configuration list", urlPattern);
                } else {
                    log.warn("Constraint configuration '{}' has no valid 'allowedTypes' regex pattern. Discarding this constraint", urlPattern);
                }
            } catch (PatternSyntaxException patternException) {
                log.warn("Constraint '{}' is not a valid regex pattern. Discarding this constraint", urlPattern);
            }
        }

        return constraints;
    }

    private static List<PatternHolder> extractAllowedTypes(JsonObject constraintObj){
        List<PatternHolder> allowedTypes = new ArrayList<>();
        JsonArray allowedTypesArray = constraintObj.getJsonArray(ALLOWED_TYPES);
        if(allowedTypesArray == null){
            log.warn("No '{}' array found in configuration", ALLOWED_TYPES);
            return allowedTypes;
        }

        for (Object allowedType : allowedTypesArray) {
            String allowedTypeStr = (String) allowedType;
            if(StringUtils.isNotEmptyTrimmed(allowedTypeStr)) {
                try {
                    allowedTypes.add(new PatternHolder(Pattern.compile(allowedTypeStr).pattern()));
                } catch (PatternSyntaxException patternException) {
                    log.warn("Content-Type constraint '{}' is not a valid regex pattern. Discarding this constraint", allowedTypeStr);
                }
            } else {
                log.warn("Content-Type constraint '{}' is not allowed to be empty. Discarding this constraint", allowedTypeStr);
            }
        }

        return allowedTypes;
    }
}
