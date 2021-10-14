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
        if(statusCode != null) {
            debug(request, statusCode.getStatusCode(), statusCode.getStatusMessage(), caller);
        } else {
            RequestLoggerFactory.getLogger(caller, request).debug(responseLogStringUnknownStatusCode(request));
        }
    }

    /**
     * Logs a debug message with the provided status code and status message and request information
     *
     * @param request request
     * @param statusCode statusCode
     * @param statusMessage statusMessage
     * @param caller caller
     */
    public static void debug(HttpServerRequest request, int statusCode, String statusMessage, Class<?> caller) {
        if (request != null && statusMessage != null && caller != null && !request.headers().contains(SELF_REQUEST_HEADER)) {
            RequestLoggerFactory.getLogger(caller, request).debug(responseLogString(request, statusCode, statusMessage));
        }
        removeSelfRequestHeaders(request);
    }

    /**
     * Logs an info message with the provided status code and request information
     *
     * @param request request
     * @param statusCode statusCode
     * @param caller caller
     */
    public static void info(HttpServerRequest request, StatusCode statusCode, Class<?> caller) {
        if(statusCode != null) {
            info(request, statusCode.getStatusCode(), statusCode.getStatusMessage(), caller);
        } else {
            RequestLoggerFactory.getLogger(caller, request).info(responseLogStringUnknownStatusCode(request));
        }
    }

    /**
     * Logs an info message with the provided status code and request information
     *
     * @param request request
     * @param statusCode statusCode
     * @param caller caller
     */
    public static void info(HttpServerRequest request, int statusCode, String statusMessage, Class<?> caller) {
        if (request != null && statusMessage != null && caller != null && !request.headers().contains(SELF_REQUEST_HEADER)) {
            RequestLoggerFactory.getLogger(caller, request).info(responseLogString(request, statusCode, statusMessage));
        }
        removeSelfRequestHeaders(request);
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

    private static String responseLogString(HttpServerRequest request, int statusCode, String statusMessage) {
        return "Responding " + request.method() + " request to " + request.uri() + " with status code " + statusCode + " " + statusMessage;
    }

    private static String responseLogStringUnknownStatusCode(HttpServerRequest request) {
        return "Responding " + request.method() + " request to " + request.uri() + " with unknown status code";
    }

    private static void removeSelfRequestHeaders(HttpServerRequest request){
        if(request != null) {
            request.headers().remove(SELF_REQUEST_HEADER);
        }
    }
}
