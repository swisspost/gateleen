package org.swisspush.gateleen.core.exception;

/**
 * Basically same as in vertx, But adding the forgotten contructors.
 */
public class GateleenNoStacktraceException extends RuntimeException {

    public GateleenNoStacktraceException() {
    }

    public GateleenNoStacktraceException(String message) {
        super(message);
    }

    public GateleenNoStacktraceException(String message, Throwable cause) {
        super(message, cause);
    }

    public GateleenNoStacktraceException(Throwable cause) {
        super(cause);
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

}
