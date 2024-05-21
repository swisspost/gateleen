package org.swisspush.gateleen.core.exception;

import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;


/**
 * Applies dependency inversion for exception instantiation.
 *
 * This class did arise because we had different use cases in different
 * applications. One of them has the need to perform fine-grained error
 * reporting. Whereas in the other application this led to performance issues.
 * So now through this abstraction, both applications can choose the behavior
 * they need.
 *
 * If dependency-injection gets applied properly, an app can even provide its
 * custom implementation to fine-tune the exact behavior even further.
 */
public interface GateleenExceptionFactory {

    public Exception newException(String message, Throwable cause);

    public ReplyException newReplyException(ReplyFailure failureType, int failureCode, String message);


    /**
     * See {@link GateleenThriftyExceptionFactory}.
     */
    public static GateleenExceptionFactory newGateleenThriftyExceptionFactory() {
        return new GateleenThriftyExceptionFactory();
    }

    /**
     * See {@link GateleenWastefulExceptionFactory}.
     */
    public static GateleenExceptionFactory newGateleenWastefulExceptionFactory() {
        return new GateleenWastefulExceptionFactory();
    }

}
