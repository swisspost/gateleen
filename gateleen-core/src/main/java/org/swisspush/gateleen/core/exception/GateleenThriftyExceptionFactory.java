package org.swisspush.gateleen.core.exception;

import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;

/**
 * See {@link GateleenExceptionFactory} for details.
 */
class GateleenThriftyExceptionFactory implements GateleenExceptionFactory {

    GateleenThriftyExceptionFactory() {
    }

    public Exception newException(String message, Throwable cause) {
        if (cause instanceof Exception) return (Exception) cause;
        return new GateleenNoStacktraceException(message, cause);
    }

    @Override
    public ReplyException newReplyException(ReplyFailure failureType, int failureCode, String message) {
        return new GateleenNoStackReplyException(failureType, failureCode, message);
    }

}
