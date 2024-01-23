package org.swisspush.gateleen.validation;

import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Utility class providing functions for working with {@link ValidationResource} and {@link SchemaLocation}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public final class ValidationUtil {

    private ValidationUtil() {}

    /**
     * Get values of matching {@link ValidationResource} for provided {@link HttpServerRequest}
     *
     * @param validationResource the {@link ValidationResource} holding the configuration values
     * @param request the {@link HttpServerRequest} to lookup
     * @param log the {@link Logger}
     * @return a {@link Map} holding the corresponding configuration values or <code>null</code>
     */
    public static Map<String, String> matchingValidationResourceEntry(ValidationResource validationResource, HttpServerRequest request, Logger log) {
        List<Map<String, String>> validationResources = validationResource.getResources();
        try {
            for (Map<String, String> entry : validationResources) {
                if (doesRequestValueMatch(request.method().name(), entry.get(ValidationResource.METHOD_PROPERTY))
                        && doesRequestValueMatch(request.uri(), entry.get(ValidationResource.URL_PROPERTY))) {
                    return entry;
                }
            }
        } catch (PatternSyntaxException patternException) {
            log.error("{} {}", patternException.getMessage(), patternException.getPattern());
        }

        return null;
    }

    /**
     * Get a {@link SchemaLocation} (when present) of the matching {@link ValidationResource} for provided {@link HttpServerRequest}
     *
     * @param validationResource the {@link ValidationResource} holding the configuration values
     * @param request the {@link HttpServerRequest} to lookup
     * @param log the {@link Logger}
     * @return the {@link SchemaLocation} if present in the configuration values. Otherwise returns {@link Optional#empty()}
     */
    public static Optional<SchemaLocation> matchingSchemaLocation(ValidationResource validationResource, HttpServerRequest request, Logger log) {
        Map<String, String> entry = matchingValidationResourceEntry(validationResource, request, log);
        if (entry != null) {
            String location = entry.get(ValidationResource.SCHEMA_LOCATION_PROPERTY);
            if(location == null) {
                return Optional.empty();
            }

            String keepInMemoryStr = entry.get(ValidationResource.SCHEMA_KEEP_INMEMORY_PROPERTY);

            Integer keepInMemory = null;
            if(keepInMemoryStr != null) {
                try {
                    keepInMemory = Integer.parseInt(keepInMemoryStr);
                } catch (NumberFormatException ex) {
                    log.warn("Property 'keepInMemory' is not a number but " + keepInMemoryStr, ex);
                }
            }

            return Optional.of(new SchemaLocation(location, keepInMemory));
        }
        return Optional.empty();
    }

    private static boolean doesRequestValueMatch(String value, String valuePattern) {
        Pattern pattern = Pattern.compile(valuePattern);
        Matcher matcher = pattern.matcher(value);
        return matcher.matches();
    }
}
