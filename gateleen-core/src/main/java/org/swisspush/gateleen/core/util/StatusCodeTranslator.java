package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;


/**
 * Helps to translate status codes.
 *
 * @author https://github.com/ljucam [Mario Aerni]
 */
public class StatusCodeTranslator {
    private static final String TRANSLATE_PATTERN = "x-translate-status-";

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
            translatedStatus = headers.get(TRANSLATE_PATTERN + statusCode);
            if (translatedStatus == null) {
                translatedStatus = headers.get(TRANSLATE_PATTERN + (statusCode / 100) + "xx");
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
     * Creates a new MultiMap based on the given headers but
     * without any 'x-translate-status-xxx' entries.
     *
     * @param headers original headers
     * @return a copy of the original headers without any translate headers
     */
    public static MultiMap getTranslateFreeHeaders(MultiMap headers) {
        MultiMap result = MultiMap.caseInsensitiveMultiMap();

        headers.forEach( entry -> {
            if ( ! entry.getKey().startsWith(TRANSLATE_PATTERN) ) {
                result.add(entry.getKey(), entry.getValue());
            }
        });

        return result;
    }
}
