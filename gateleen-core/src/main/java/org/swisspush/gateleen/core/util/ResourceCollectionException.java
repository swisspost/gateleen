package org.swisspush.gateleen.core.util;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ResourceCollectionException extends Exception {

    private final StatusCode statusCode;

    public ResourceCollectionException(String message) {
        this(message, StatusCode.INTERNAL_SERVER_ERROR);
    }

    public ResourceCollectionException(StatusCode statusCode) {
        this(statusCode.getStatusMessage(), statusCode);
    }

    public ResourceCollectionException(String message, StatusCode statusCode) {
        super(message != null ? message : statusCode.getStatusMessage());
        this.statusCode = statusCode;
    }

    public StatusCode getStatusCode() {
        return statusCode;
    }
}
