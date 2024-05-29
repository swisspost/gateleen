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
 * There are two default options an app can use (if it does not want to provide
 * a custom impl).
 * One is {@link GateleenThriftyExceptionFactory}. It trades maintainability
 * for speed. For example prefers lightweight exceptions without stacktrace
 * recording. Plus it may apply other tricks to reduce resource costs.
 * The other one is {@link GateleenWastefulExceptionFactory}. It trades speed
 * for maintainability. So it tries to track as much error details as possible.
 * For example recording stack traces, keeping 'cause' and 'suppressed'
 * exceptions, plus maybe more.
 *
 * If none of those defaults matches, an app can provide its custom
 * implementation via dependency injection.
 */
public interface GateleenExceptionFactory {

    /** Convenience overload for {@link #newException(String, Throwable)}. */
    public default Exception newException(String msg){ return newException(msg, null); }

    /** Convenience overload for {@link #newException(String, Throwable)}. */
    public default Exception newException(Throwable cause){ return newException(null, cause); }

    public Exception newException(String msg, Throwable cause);

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
