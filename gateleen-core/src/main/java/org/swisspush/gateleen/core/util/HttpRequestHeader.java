package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;

/**
 * Enum for HTTP request headers used in gateleen
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public enum HttpRequestHeader {
    CONTENT_LENGTH("Content-Length");

    private final String name;

    HttpRequestHeader(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static boolean containsHeader(MultiMap headers, HttpRequestHeader httpRequestHeader){
        if(headers == null){
            return false;
        }
        return headers.contains(httpRequestHeader.getName());
    }

    /**
     * Get the value of the provided {@link HttpRequestHeader} as Integer.
     * <p>Returns <code>null</code> in the following cases:</p>
     *
     * <ul>
     *     <li>headers are <code>null</code></li>
     *     <li>headers does not contain httpRequestHeader</li>
     *     <li>httpRequestHeader is no parsable Integer i.e. empty string, non-digit characters, numbers to bigger than Integer allows</li>
     * </ul>
     *
     * @param headers the http request headers
     * @param httpRequestHeader the http request header to get the value from
     * @return an Integer representing the value of the httpRequestHeader or null
     */
    public static Integer getInteger(MultiMap headers, HttpRequestHeader httpRequestHeader) {
        String headerValue = null;
        if(headers != null) {
            headerValue = headers.get(httpRequestHeader.getName());
        }

        try {
            return Integer.parseInt(headerValue);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the value of the provided {@link HttpRequestHeader} as String.
     * <p>Returns <code>null</code> in the following cases:</p>
     *
     * <ul>
     *     <li>headers are <code>null</code></li>
     * </ul>
     *
     * @param headers the http request headers
     * @param httpRequestHeader the http request header to get the value from
     * @return a String representing the value of the httpRequestHeader or null
     */
    public static String getString(MultiMap headers, HttpRequestHeader httpRequestHeader) {
        if(headers == null) {
            return null;
        }
        return headers.get(httpRequestHeader.getName());
    }
}
