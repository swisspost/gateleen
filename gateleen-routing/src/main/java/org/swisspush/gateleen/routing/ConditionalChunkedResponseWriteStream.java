package org.swisspush.gateleen.routing;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.WriteStream;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enables chunked transfer encoding immediately before forwarding the first
 * non-empty response buffer.
 */
final class ConditionalChunkedResponseWriteStream implements WriteStream<Buffer> {

    private final HttpServerResponse delegate;
    private final boolean useChunkedTransfer;
    private final AtomicBoolean payloadWritten = new AtomicBoolean();

    ConditionalChunkedResponseWriteStream(HttpServerResponse delegate, boolean useChunkedTransfer) {
        this.delegate = delegate;
        this.useChunkedTransfer = useChunkedTransfer;
    }

    private void prepareForPayload(Buffer data) {
        if (useChunkedTransfer && data.length() > 0 && payloadWritten.compareAndSet(false, true)) {
            delegate.setChunked(true);
        }
    }

    @Override
    public Future<Void> write(Buffer data) {
        prepareForPayload(data);
        return delegate.write(data);
    }

    @Override
    public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
        prepareForPayload(data);
        delegate.write(data, handler);
    }

    @Override
    public Future<Void> end() {
        return delegate.end();
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        delegate.end(handler);
    }

    @Override
    public Future<Void> end(Buffer data) {
        prepareForPayload(data);
        return delegate.end(data);
    }

    @Override
    public void end(Buffer data, Handler<AsyncResult<Void>> handler) {
        prepareForPayload(data);
        delegate.end(data, handler);
    }

    @Override
    public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        delegate.exceptionHandler(handler);
        return this;
    }

    @Override
    public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        delegate.setWriteQueueMaxSize(maxSize);
        return this;
    }

    @Override
    public boolean writeQueueFull() {
        return delegate.writeQueueFull();
    }

    @Override
    public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        delegate.drainHandler(handler);
        return this;
    }
}
