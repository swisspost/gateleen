package org.swisspush.gateleen.core.event;

import io.vertx.core.AsyncResult;

/** Exists because I was not able to find such an implementation in vertx.jar */
public class SucceededAsyncResult<E> implements AsyncResult<E> {
    private final E result;

    public SucceededAsyncResult(E result) {
        this.result = result;
    }

    @Override
    public E result() {
        return result;
    }

    @Override
    public Throwable cause() {
        return null;
    }

    @Override
    public boolean succeeded() {
        return true;
    }

    @Override
    public boolean failed() {
        return false;
    }
}
