package org.swisspush.gateleen.routing;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Response-side counterpart to {@link AutomaticChunkedRequestTransfer}.
 *
 * <p>Enables chunked transfer encoding immediately before forwarding the first
 * <b>non-empty</b> response buffer. This intentionally differs from
 * {@link AutomaticChunkedRequestTransfer}, which enables chunked transfer on the
 * first buffer regardless of its size: on the request side any first buffer
 * (even an empty one) proves that a body is being sent, whereas on the
 * response side an upstream response carrying {@code Transfer-Encoding: chunked}
 * but never writing any payload must not be forwarded as chunked, otherwise
 * Vert.x would produce a "chunked response without body".
 */
final class AutomaticChunkedResponseTransfer extends AbstractChunkedTransfer<HttpServerResponse> {

    private final boolean useChunkedTransfer;
    private final AtomicBoolean payloadWritten = new AtomicBoolean();

    AutomaticChunkedResponseTransfer(HttpServerResponse delegate, boolean useChunkedTransfer, String dbgHint) {
        super(delegate, dbgHint);
        this.useChunkedTransfer = useChunkedTransfer;
    }

    @Override
    protected void setDelegateChunked() {
        delegate.setChunked(true);
    }

    @Override
    protected boolean isDelegateChunked() {
        return delegate.isChunked();
    }

    private void prepareForPayload(Buffer data) {
        if (useChunkedTransfer && data.length() > 0 && payloadWritten.compareAndSet(false, true)) {
            enableChunkedTransfer();
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
        delegate.write(data).onComplete(ar -> {
            if (ar.failed()) {
                publishError(ar.cause(), handler);
            } else if (handler != null) {
                handler.handle(ar);
            }
        });
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
        delegate.end(data).onComplete(ar -> {
            if (ar.failed()) {
                publishError(ar.cause(), handler);
            } else if (handler != null) {
                handler.handle(ar);
            }
        });
    }
}
