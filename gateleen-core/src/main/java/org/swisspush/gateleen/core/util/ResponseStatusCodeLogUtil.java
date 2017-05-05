package org.swisspush.gateleen.core.util;

import io.vertx.core.http.HttpServerRequest;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;

/**
 * Utility class to log response status codes
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public final class ResponseStatusCodeLogUtil {

    private static final String SELF_REQUEST_HEADER = "x-self-request";

    private ResponseStatusCodeLogUtil() {
    }

    /**
     * Logs a debug message with the provided status code and request information
     *
     * @param request request
     * @param statusCode statusCode
     * @param caller caller
     */
    public static void debug(HttpServerRequest request, StatusCode statusCode, Class<?> caller) {
        if (request != null && statusCode != null && caller != null && !request.headers().contains(SELF_REQUEST_HEADER)) {
            RequestLoggerFactory.getLogger(caller, request).debug("Responding " + request.method() + " request to " + request.uri() + " with status code " + statusCode);
        }
        request.headers().remove(SELF_REQUEST_HEADER);
    }

    /**
     * Logs an info message with the provided status code and request information
     *
     * @param request request
     * @param statusCode statusCode
     * @param caller caller
     */
    public static void info(HttpServerRequest request, StatusCode statusCode, Class<?> caller) {
        if (request != null && statusCode != null && caller != null && !request.headers().contains(SELF_REQUEST_HEADER)) {
            RequestLoggerFactory.getLogger(caller, request).info("Responding " + request.method() + " request to " + request.uri() + " with status code " + statusCode);
        }
        request.headers().remove(SELF_REQUEST_HEADER);
    }

    /**
     * Check whether the request targets an external resource or not
     *
     * @param target target
     * @return boolean
     */
    public static boolean isRequestToExternalTarget(String target) {
        boolean isInternalRequest = false;
        if (target != null) {
            isInternalRequest = target.contains("localhost") || target.contains("127.0.0.1");
        }
        return !isInternalRequest;
    }
}
