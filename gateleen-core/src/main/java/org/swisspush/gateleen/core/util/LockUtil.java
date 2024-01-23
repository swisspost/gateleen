package org.swisspush.gateleen.core.util;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lock.Lock;

/**
 * Class HttpServerRequestUtil.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class LockUtil {

    private LockUtil(){}

    /**
     * Acquire a lock. Resolves always to <code>Boolean.TRUE</code> when no lock implementation is provided
     *
     * @param lockImpl the lock implementation
     * @param lock the lock
     * @param token the unique token
     * @param lockExpiryMs the expiry of the lock
     * @param log the logger
     * @return A boolean {@link Future} whether the lock could has been acquired or not
     */
    public static Future<Boolean> acquireLock(Lock lockImpl, String lock, String token, long lockExpiryMs, Logger log){
        Promise<Boolean> promise = Promise.promise();

        if(lockImpl == null){
            log.info("No lock implementation defined, going to pretend like we got the lock");
            promise.complete(Boolean.TRUE);
            return promise.future();
        }

        log.debug("Trying to acquire lock '{}' with token '{}' and expiry {}ms", lock, token, lockExpiryMs);
        lockImpl.acquireLock(lock, token, lockExpiryMs).onComplete(lockEvent -> {
            if(lockEvent.succeeded()){
                if(lockEvent.result()){
                    log.debug("Acquired lock '{}' with token '{}'", lock, token);
                    promise.complete(Boolean.TRUE);
                } else {
                    promise.complete(Boolean.FALSE);
                }
            } else {
                promise.fail(lockEvent.cause());
            }
        });

        return promise.future();
    }

    /**
     * Release a lock.
     *
     * @param lockImpl the lock implementation
     * @param lock the lock
     * @param token the unique token
     * @param log the Logger
     */
    public static void releaseLock(Lock lockImpl, String lock, String token, Logger log){
        if(lockImpl == null){
            log.info("No lock implementation defined, going to pretend like we released the lock");
            return;
        }
        log.debug("Trying to release lock '{}' with token '{}'", lock, token);
        lockImpl.releaseLock(lock, token).onComplete(releaseEvent -> {
            if(releaseEvent.succeeded()){
                if(releaseEvent.result()){
                    log.debug("Released lock '{}' with token '{}'", lock, token);
                }
            } else {
                log.error("Could not release lock '{}'.", lock, new Exception("stacktrace", releaseEvent.cause()));
            }
        });
    }
}
