package org.swisspush.gateleen.routing;

import org.slf4j.Logger;
import org.swisspush.gateleen.core.util.StatusCodeTranslator;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enhances the {@link org.swisspush.gateleen.core.util.StatusCodeTranslator } with features
 * needed explicitly in the Router.
 * 
 * @author https://github.com/ljucam [Mario Aerni]
 */
public class Translator extends StatusCodeTranslator {

    /**
     * Checks if there exists a handling for the given <code>statusCode</code> in the
     * rules for this request.
     * 
     * @param statusCode the original status code
     * @param rule the rule matching the current request
     * @return the translated status or if no translation was carried out the original status code.
     */
    public static int translateStatusCode(int statusCode, Rule rule) {
        return translateStatusCode(statusCode, rule, null);
    }

    /**
     * Checks if there exists a handling for the given <code>statusCode</code> in the
     * rules for this request.
     * 
     * @param statusCode the original status code
     * @param rule the rule matching the current request
     * @param log extra configured logger
     * @return the translated status or if no translation was carried out the original status code.
     */
    public static int translateStatusCode(int statusCode, Rule rule, Logger log) {
        Integer translatedStatus = null;

        if (rule.getTranslateStatus() != null) {
            for (Map.Entry<Pattern, Integer> entry : rule.getTranslateStatus().entrySet()) {
                if (entry.getKey().matcher("" + statusCode).matches()) {
                    if (log != null) {
                        log.warn("Translated status " + statusCode + " to " + entry.getValue());
                    }
                    translatedStatus = entry.getValue();
                    break;
                }
            }
        }

        // if something is found, return found value
        if (translatedStatus != null) {
            return translatedStatus;
        }
        // else return original value
        else {
            return statusCode;
        }
    }
}
