package org.swisspush.gateleen.core.util;

import io.vertx.core.AsyncResult;

public class FailedAsyncResult<T> implements AsyncResult<T> {
    private final Throwable cause;

    public FailedAsyncResult(Throwable cause) {
        this.cause = cause;
    }

    @Override
    public T result() {
        return null;
    }

    @Override
    public Throwable cause() {
        return cause;
    }

    @Override
    public boolean succeeded() {
        return false;
    }

    @Override
    public boolean failed() {
        return true;
    }
}
