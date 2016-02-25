package org.swisspush.gateleen.core.util;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.hash.Hashing;

/**
 * Generator for HashCode values.
 * 
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public final class HashCodeGenerator {

    private HashCodeGenerator() {
        // Prevent instantiation
    }

    /**
     * Joins the given parameters with a + and calculates the HashCode.
     * Returns <code>null</code> if uri and payload are <code>null</code>
     *
     * @param uri uri
     * @param payload payload
     * @return the HashCode of the concatenation (+) of the given parameters
     */
    public static String createHashCode(String uri, String payload) {
        String concatenated = trimIfNotNull(uri) + trimIfNotNull(payload);
        if (concatenated == null) {
            return null;
        }
        return Hashing.murmur3_128().hashString(concatenated, Charsets.UTF_8).toString();
    }

    /**
     * Returns the SHA256 hash of the provided String or <code>null</code> if <code>null</code> has been provided.
     *
     * @param dataToHash dataToHash
     * @return String
     */
    public static String createSHA256HashCode(String dataToHash) {
        if (dataToHash == null) {
            return null;
        }
        return Hashing.sha256().hashString(trimIfNotNull(dataToHash), Charsets.UTF_8).toString();
    }

    private static String trimIfNotNull(String stringToTrim) {
        if (!Strings.isNullOrEmpty(stringToTrim)) {
            return stringToTrim.trim();
        }
        return stringToTrim;
    }
}
