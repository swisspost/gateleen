package org.swisspush.gateleen.core.http;

/**
 * Parsing utility. Stolen from vert.x code because it is hidden there.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
final class UriParser {

    private UriParser() {

    }

    /**
     * Extract the path out of the uri.
     *
     */
    static String path(String uri) {
        int i = uri.indexOf("://");
        if (i == -1) {
            i  = 0;
        } else {
            i  = uri.indexOf('/', i + 3);
            if (i == -1) {
                // contains no /
                return "/";
            }
        }

        int queryStart = uri.indexOf('?', i);
        if (queryStart == -1) {
            queryStart = uri.length();
        }
        return uri.substring(i, queryStart);
    }

    /**
     * Extract the query out of a uri or returns {@code null} if no query was found.
     */
    static String query(String uri) {
        int i = uri.indexOf('?');
        if (i == -1) {
            return null;
        } else {
            return uri.substring(i + 1 , uri.length());
        }
    }

}
