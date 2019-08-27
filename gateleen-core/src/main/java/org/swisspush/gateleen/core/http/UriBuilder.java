package org.swisspush.gateleen.core.http;


import java.util.regex.Pattern;

/**
 * Helper Class for URI manipulations
 */
public class UriBuilder {

    /**
     * Prevent instantiation of this class
     */
    private UriBuilder() {
    }

    /**
     * Concatenates multple URI segments in a save way and makes sure that only one slash is in between each
     *
     * @param segments URI segments to put together in a valid manner
     * @return The concatenated resulting Uri
     */
    public static String concatUriSegments(String... segments) {
        StringBuffer uri = new StringBuffer();
        for (int i = 0; i < segments.length; i++) {
            if (i == 0) {
                uri = uri.append(segments[0]);
            } else {
                uri = uri.append("/").append(segments[i]);
            }
        }
        return cleanUri(uri.toString());
    }

    /**
     * Clean the given URI from any multi slash delimiters in between uri segments
     *
     * @param uri to be cleaned from possible multi slash delimiters
     * @return The cleaned uri
     */
    public static String cleanUri(String uri) {
        return uri.replaceAll("(?<!(http:|https:))/+", "/");
    }


}
