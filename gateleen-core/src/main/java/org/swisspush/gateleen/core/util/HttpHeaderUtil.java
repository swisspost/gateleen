package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;


public class HttpHeaderUtil {
    private static final Logger LOG = LoggerFactory.getLogger(HttpHeaderUtil.class);

    /**
     * <p>Removes headers which MUST NOT be forwarded by proxies.</p>
     *
     * <ul>
     *     <li>This MAY modifies the passed headers.</li>
     *     <li>This MAY returns the same (modified) instance as passed.</li>
     * </ul>
     *
     * @param headers The headers to check.
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.10">RFC 2616 section 14.10</a>
     */
    public static <T extends MultiMap> T removeNonForwardHeaders(T headers) {
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
    public @Nullable
    static <T extends MultiMap> String getHeaderValue(@Nonnull T headers, @Nonnull String headerKey) {
        for (Entry<String, String> entry : headers.entries()) {
            if (entry.getKey().equalsIgnoreCase(headerKey)) {
                String value = entry.getValue();
                if (value != null) {
                    return value.trim();
                }
                return null;
            }
        }
        return null;
    }

    /**
     * Helper method to check the presence of a http header matching the provided pattern. Every header entry is checked
     * in the format KEY: VALUE
     *
     * @param headers        The Map with the header key - value pairs to be evaluated
     * @param headersPattern The pattern to match the headers against.
     * @return True if a matching header was found, false otherwise
     */
    public static <T extends MultiMap> boolean hasMatchingHeader(@Nonnull T headers, @Nonnull Pattern headersPattern) {
        for (Map.Entry<String, String> entry : headers) {
            String header = entry.getKey() + ": " + entry.getValue();
            if (headersPattern.matcher(header).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merges headers, makes sure that only one value of header ends up in the result.
     *
     * @param context optional context information to be used for logging purposes, not used for the actual merge
     */
    public static void mergeHeaders(@Nonnull MultiMap destination, @Nonnull MultiMap source, @Nullable String context) {
        source.forEach(sourceHeader -> {
            if (destination.contains(sourceHeader.getKey())) {
                String destinationValue = destination.get(sourceHeader.getKey());
                String sourceValue = source.get(sourceHeader.getKey());
                if (!(destinationValue.isEmpty() || sourceValue.isEmpty())) {
                    if (!destinationValue.equals(sourceValue)) {
                        LOG.error("{}} values do not match {} != {} for request {}",
                                sourceHeader.getKey(), destinationValue, sourceValue, context);
                    }
                    destination.remove(sourceHeader.getKey());
                }
            }
        });
        destination.addAll(source);
    }
}

