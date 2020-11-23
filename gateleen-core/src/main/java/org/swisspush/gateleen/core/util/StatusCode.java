package org.swisspush.gateleen.core.util;

/**
 * Enum for HTTP status codes
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public enum StatusCode {
    OK(200, "OK"),
    ACCEPTED(202, "Accepted"),
    NOT_MODIFIED(304, "Not Modified"),
    BAD_REQUEST(400, "Bad Request"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
    CONFLICT(409, "Conflict"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    BAD_GATEWAY(502, "Bad Gateway"),
    SERVICE_UNAVAILABLE(503, "Service Unavailable"),
    TIMEOUT(504,"Gateway Timeout"),
    INSUFFICIENT_STORAGE(507, "Insufficient Storage");

    private final int statusCode;
    private final String statusMessage;

    StatusCode(int statusCode, String statusMessage) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    @Override
    public String toString() {
        return statusCode + " " + statusMessage;
    }

    /**
     * Returns the enum StatusCode which matches the specified http status code.
     *
     * @param code code
     * @return The matching StatusCode or null if none matches.
     */
    public static StatusCode fromCode(int code) {
        for (StatusCode statuscode : values()) {
            if (statuscode.getStatusCode() == code) {
                return statuscode;
            }
        }
        return null;
    }
}
