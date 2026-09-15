package org.swisspush.gateleen.routing;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Common base for {@link AutomaticChunkedRequestTransfer} and {@link AutomaticChunkedResponseTransfer}.
 *
 * <p>Both subclasses wrap a Vert.x {@link WriteStream} (an outgoing http request resp. response) to
 * lazily call {@code setChunked(true)} on the delegate once it becomes clear that a body will be
 * written. This base class only holds the plumbing which is truly identical between both siblings:
 * the {@code dbgHint}, the {@link #exceptionHandler}/{@link #setWriteQueueMaxSize}/{@link #writeQueueFull}/
 * {@link #drainHandler} delegation, and the "no handler was able to handle this error" fallback logic.
 *
 * <p>The actual body-tracking rule (WHEN to flip to chunked) and the {@code write}/{@code end} semantics
 * intentionally stay in the subclasses, as they genuinely differ (see the subclasses' Javadoc).
 *
 * @param <D> concrete delegate type ({@link io.vertx.core.http.HttpClientRequest} resp.
 *            {@link io.vertx.core.http.HttpServerResponse}).
 */
abstract class AbstractChunkedTransfer<D extends WriteStream<Buffer>> implements WriteStream<Buffer> {

    protected final Logger log = getLogger(getClass());
    protected final D delegate;
    protected final String dbgHint;
    private Handler<Throwable> exceptionHandler;

    protected AbstractChunkedTransfer(D delegate, String dbgHint) {
        assert delegate != null : "delegate != null";
        assert !isNullOrEmpty(dbgHint) : "An empty dbgHint is worth NOTHING!";
        this.delegate = delegate;
        this.dbgHint = dbgHint;
    }

    /**
     * Calls {@code setChunked(true)} on the concrete delegate and warns if it did not stick.
     * Subclasses call this once they determined (based on their own rule) that now is the
     * right time to switch to chunked transfer.
     */
    protected void enableChunkedTransfer() {
        setDelegateChunked();
        if (!isDelegateChunked()) log.debug(
                "WTF?!? setChunked(true), but isChunked() still returns 'false': {}",
                delegate.getClass());
    }

    /** Calls the concrete delegate's {@code setChunked(true)}. */
    protected abstract void setDelegateChunked();

    /** Calls the concrete delegate's {@code isChunked()}. */
    protected abstract boolean isDelegateChunked();

    /**
     * Tries the given per-call handler first, then the generic {@link #exceptionHandler}, and
     * finally falls back to logging the error, in case none of them was able to handle it.
     */
    protected void publishError(Throwable ex, Handler<AsyncResult<Void>> regularHandler) {
        assert ex != null : "ex != null";
        var exAsFuture = Future.<Void>failedFuture(ex);
        /* first give the WRITE handler a chance to do its job. */
        try {
            if (regularHandler != null) {
                regularHandler.handle(exAsFuture);
                return; /* error successfully handled. Done. */
            }
        } catch (RuntimeException ex2) {
            log.debug("{}: write handler failed to handle error", dbgHint, ex2);
        }
        /* then try the generic handler. */
        try {
            if (exceptionHandler != null) {
                exceptionHandler.handle(ex);
                return; /* error successfully handled. We're done. */
            }
        } catch (RuntimeException ex2) {
            log.debug("{}: exception handler failed to handle error", dbgHint, ex2);
        }
        /* No handler was able to handle the exception (either there was no
         * handler or it failed). So log it here as a last resort. */
        log.error("{}: {}", dbgHint, ex.getMessage(), log.isDebugEnabled() ? ex : null);
    }

    @Override
    public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        delegate.exceptionHandler(handler);
        /* keep a ref for ourself, as we may stumble over errors too. */
        this.exceptionHandler = handler;
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
