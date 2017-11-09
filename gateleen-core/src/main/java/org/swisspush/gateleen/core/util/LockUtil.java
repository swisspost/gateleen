package org.swisspush.gateleen.core.util;

import io.vertx.core.Future;
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
        Future<Boolean> future = Future.future();

        if(lockImpl == null){
            log.info("No lock implementation defined, going to pretend like we got the lock");
            future.complete(Boolean.TRUE);
            return future;
        }

        log.debug("Trying to acquire lock '" + lock + "' with token '" + token + "' and expiry " + lockExpiryMs + "ms");
        lockImpl.acquireLock(lock, token, lockExpiryMs).setHandler(lockEvent -> {
            if(lockEvent.succeeded()){
                if(lockEvent.result()){
                    log.debug("Acquired lock '" + lock + "' with token '" + token + "'");
                    future.complete(Boolean.TRUE);
                } else {
                    future.complete(Boolean.FALSE);
                }
            } else {
                future.fail(lockEvent.cause());
            }
        });

        return future;
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
        log.debug("Trying to release lock '" + lock + "' with token '" + token + "'");
        lockImpl.releaseLock(lock, token).setHandler(releaseEvent -> {
            if(releaseEvent.succeeded()){
                if(releaseEvent.result()){
                    log.debug("Released lock '" + lock + "' with token '" + token + "'");
                }
            } else {
                log.error("Could not release lock '"+lock+"'. Message: " + releaseEvent.cause().getMessage());
            }
        });
    }
}
