package org.swisspush.gateleen.core.exception;

public class NoStackIllegalStateException extends IllegalStateException {

    public NoStackIllegalStateException() {
        super();
    }

    public NoStackIllegalStateException(String s) {
        super(s);
    }

    public NoStackIllegalStateException(String message, Throwable cause) {
        super(message, cause);
    }

    public NoStackIllegalStateException(Throwable cause) {
        super(cause);
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

}
