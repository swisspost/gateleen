package org.swisspush.gateleen.core.lock;

import io.vertx.core.Future;

/**
 * Cluster wide locks allow you to obtain exclusive locks across the cluster.
 * This is useful when you want to do something or access a resource on only one node of a cluster at any one time.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface Lock {
    /**
     * Try to acquire a lock.
     * The <code>token</code> parameter value must be unique across all clients and all lock requests. The <code>lockExpiryMs</code>
     * parameter defines the expiry of the lock.
     * When not manually released, the lock will be released automatically when expired.
     *
     * @param lock The name of the lock to acquire
     * @param token A unique token to define the owner of the lock
     * @param lockExpiryMs The lock expiry in milliseconds
     * @return Returns a Future holding a Boolean value whether the lock could be successfully acquired or not
     */
    Future<Boolean> acquireLock(String lock, String token, long lockExpiryMs);

    /**
     * Try to release a lock.
     * The <code>token</code> parameter value is used to verify that only the owner of the lock can release it.
     * The <code>token</code> parameter value also prevents the original owner of an already expired lock to release a lock
     * which has been acquired by another client.
     *
     * @param lock The name of the lock to release
     * @param token A unique token to verify if the owner of the lock tries to release the lock
     * @return Returns a Promise holding a Boolean value whether the lock could be sucsessfully released or not
     */
    Future<Boolean> releaseLock(String lock, String token);
}
