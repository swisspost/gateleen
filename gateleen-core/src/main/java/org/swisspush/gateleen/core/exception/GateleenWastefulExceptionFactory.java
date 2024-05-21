package org.swisspush.gateleen.core.exception;

import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;

/**
 * See {@link GateleenExceptionFactory} for details.
 */
class GateleenWastefulExceptionFactory implements GateleenExceptionFactory {

    GateleenWastefulExceptionFactory() {
    }

    public Exception newException(String message, Throwable cause) {
        return new Exception(message, cause);
    }

    @Override
    public ReplyException newReplyException(ReplyFailure failureType, int failureCode, String message) {
        return new ReplyException(failureType, failureCode, message);
    }

}
