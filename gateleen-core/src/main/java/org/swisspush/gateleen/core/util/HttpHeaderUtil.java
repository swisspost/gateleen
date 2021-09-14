package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;
import java.util.Map.Entry;


public class HttpHeaderUtil {

    /**
     * <p>Removes headers which MUST NOT be forwarded by proxies.</p>
     *
     * <ul>
     *     <li>This MAY modifies the passed headers.</li>
     *     <li>This MAY returns the same (modified) instance as passed.</li>
     * </ul>
     *
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.10">RFC 2616 section 14.10</a>
     *
     * @param headers
     *      The headers to check.
     */
    public static <T extends MultiMap> T removeNonForwardHeaders( T headers ) {
        final String CONNECTION = HttpRequestHeader.CONNECTION.getName();

        // Remove all headers named by connection-token.
        headers.getAll(CONNECTION).forEach(headers::remove);

        // Remove the connection headers itself.
        headers.remove(CONNECTION);

        return headers;
    }

    /**
     * Helper method to find the first occurrence of a http header within the given List of headers.
     *
     * @param headers   The Map with the header key - value pairs to be evaluated
     * @param headerKey The key for the header pair we are searching for in the given map. Note that
     *                  the key searching is non case sensitive.
     * @return The found header value or null if none found
     */
    public static <T extends MultiMap> String getHeaderValue(T headers, String headerKey) {
        String matchKey = headerKey.toLowerCase();
        for (Entry<String, String> entry : headers.entries()) {
            if (entry.getKey().toLowerCase().equals(matchKey)) {
                String value = entry.getValue();
                if (value != null) {
                    return value.trim();
                }
                return null;
            }
        }
        return null;
    }

}
