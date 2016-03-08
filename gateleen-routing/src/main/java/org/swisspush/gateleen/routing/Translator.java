package org.swisspush.gateleen.routing;

import io.vertx.core.MultiMap;
import org.slf4j.Logger;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Helps to translate status codes.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class Translator {

    /**
     * Checks if there exists a handling for the given <code>statusCode</code> in the
     * headers of the request. The header is checked for a pattern matching <code>x-translate-status-</code>
     * and the given <code>statusCode</code>.
     * 
     * @param statusCode the original status code
     * @param headers the headers of the request
     * @return the translated status or if no translation was carried out the original status code.
     */
    public static int translateStatusCode(int statusCode, MultiMap headers) {
        String translatedStatus = null;

        // it dosen't make sense to translate status code 200 ...
        if (statusCode > 200) {
            translatedStatus = headers.get("x-translate-status-" + statusCode);
            if (translatedStatus == null) {
                translatedStatus = headers.get("x-translate-status-" + (statusCode / 100) + "xx");
            }
        }

        if (translatedStatus != null) {
            try {
                // translated status code
                return Integer.parseInt(translatedStatus);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        // original status code
        return statusCode;
    }

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
