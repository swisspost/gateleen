package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;


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
    public <T extends MultiMap> T removeNonForwardHeaders( T headers ) {
        final String CONNECTION = HttpRequestHeader.CONECTION.getName();

        // Remove all headers named by connection-token.
        headers.getAll(CONNECTION).forEach(headers::remove);

        // Remove the connection headers itself.
        headers.remove(CONNECTION);

        return headers;
    }

}
