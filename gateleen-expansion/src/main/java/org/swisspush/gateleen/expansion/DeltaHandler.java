package org.swisspush.gateleen.expansion;

import io.vertx.core.Handler;

/**
 * An extension of the default Handler interface in order to have
 * the possibility to save x-delta response headers.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 * @param <E>
 */
public interface DeltaHandler<E> extends Handler<E> {

    /**
     * Checks if the given x-delta number from the response is not null and
     * is higher then the already stored one. If so the number is set otherwise
     * not.
     * 
     * @param xdeltaResponseNumber null or the current x-delta value from the response.
     */
    void storeXDeltaResponseHeader(String xdeltaResponseNumber);

}
