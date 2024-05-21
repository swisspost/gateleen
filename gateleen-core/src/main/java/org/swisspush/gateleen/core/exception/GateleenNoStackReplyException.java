package org.swisspush.gateleen.core.exception;

import io.vertx.core.eventbus.ReplyFailure;

/**
 * There was once a fix in vertx for this (https://github.com/eclipse-vertx/vert.x/issues/4840)
 * but for whatever reason in our case we still see stack-trace recordings. Passing
 * this subclass to {@link io.vertx.core.eventbus.Message#reply(Object)} seems to
 * do the trick.
 */
public class GateleenNoStackReplyException extends io.vertx.core.eventbus.ReplyException {

    public GateleenNoStackReplyException(ReplyFailure failureType, int failureCode, String message) {
        super(failureType, failureCode, message);
    }

    public GateleenNoStackReplyException(ReplyFailure failureType, String message) {
        this(failureType, -1, message);
    }

    public GateleenNoStackReplyException(ReplyFailure failureType) {
        this(failureType, -1, null);
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

}
