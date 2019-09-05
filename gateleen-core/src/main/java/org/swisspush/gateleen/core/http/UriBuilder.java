package org.swisspush.gateleen.core.http;


import java.util.regex.Pattern;

/**
 * Helper Class for URI manipulations
 */
public class UriBuilder {

    private static final Pattern URICLEANPATTERN = Pattern.compile("(?<!(http:|https:))/+");

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
        StringBuilder uri = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i == 0) {
                uri.append(segments[0]);
            } else {
                uri.append("/").append(segments[i]);
            }
        }
        return cleanUri(uri.toString());
    }

    /**
     * Clean the given URI from any multi slash delimiters in between uri segments
     * <p>
     * DANGER: This method applies a regular expression against the given uri and this takes some resources of course. Do
     * think twice before using this method.
     *
     * @param uri to be cleaned from possible multi slash delimiters
     * @return The cleaned uri
     */
    public static String cleanUri(String uri) {
        return URICLEANPATTERN.matcher(uri).replaceAll("/");
    }


}
