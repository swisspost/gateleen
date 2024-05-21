package org.swisspush.gateleen.core.exception;

import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;

/**
 * Trades maintainability for speed. For example prefers lightweight
 * exceptions without stacktrace recording. It may even decide to drop 'cause'
 * or other details. If an app needs more error details it should use
 * {@link GateleenWastefulExceptionFactory}. If none of those fits the apps needs, it
 * can provide its own implementation.
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
