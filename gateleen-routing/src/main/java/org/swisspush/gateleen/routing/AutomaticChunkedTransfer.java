package org.swisspush.gateleen.routing;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.streams.WriteStream;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Strings.isNullOrEmpty;
import static io.vertx.core.Future.succeededFuture;
import static org.slf4j.LoggerFactory.getLogger;


/**
 * Gateleen internal requests (e.g. from schedulers or delegates) often
 * have neither "Content-Length" nor "Transfer-Encoding: chunked" header.
 * So we have to wait for a body buffer to know if a body exists. Only
 * looking on the headers and/or the http-method is NOT sustainable to
 * answer this question.
 *
 * No matter what, we still MUST either {@link HttpClientRequest#setChunked(boolean)}
 * or "Content-Length" header to prevent vertx exceptions.
 *
 * Just always setting 'chunked' is incorrect, as GET, HEAD, OPTIONS, etc
 * requests sometimes DO NOT even have a body at all (nope, "no body" IS
 * NOT THE SAME as an "empty body").
 *
 * To solve this problem, this class wraps the outgoing stream of an
 * {@link HttpClientRequest} and tracks what happens on the body related
 * calls. Based on the observations there, it MAY call {@link HttpClientRequest#setChunked(boolean)}
 * to ensure correct state before data is being written to the decorated
 * request.
 */
public class AutomaticChunkedTransfer implements WriteStream<Buffer> {

    private static final Logger log = getLogger(AutomaticChunkedTransfer.class);
    private final Vertx vertx;
    private final HttpClientRequest delegate;
    private final String dbgHint;
    private final AtomicBoolean firstBuffer = new AtomicBoolean(true);
    private Handler<Throwable> exceptionHandler;

    AutomaticChunkedTransfer(Vertx vertx, HttpClientRequest delegate, String dbgHint) {
        assert vertx != null : "vertx != null";
        assert delegate != null : "delegate != null";
        assert !isNullOrEmpty(dbgHint) : "An empty dbgHint is worth NOTHING!";
        this.vertx = vertx;
        this.delegate = delegate;
        this.dbgHint = dbgHint;
    }

    private void write_(Buffer data, Handler<AsyncResult<Void>> handler) {
        /* only now we know for sure that there IS a body. */
        Future.<Void>succeededFuture().<Void>compose((Void nil) -> {
            if (firstBuffer.getAndSet(false)) {
                // avoid multiple calls due to a 'syncronized' block in HttpClient's implementation
                delegate.setChunked(true);
                if (!delegate.isChunked()) log.debug(
                        "WTF?!? setChunked(true), but isChunked() still returns 'false': {}",
                        delegate.getClass());
            }
            // Delegate
            return delegate.write(data);
        }).onFailure((Throwable ex) -> {
            log.trace("write failed: {} {}", delegate.getMethod(), delegate.getURI(), ex);
            publishError(ex, handler);
        });
    }

    private void end_(Buffer data, Handler<AsyncResult<Void>> handler) {
        Future.<Void>succeededFuture().<Object>map((Void nil) -> {
            if (data != null) { /* send WITH data */
                return delegate.end(data);
            } else { /* send WITHOUT data */
                return delegate.send();
            }
        }).<Void>map((Object unused) -> {
            handler.handle(succeededFuture());
            return null;
        }).<Void>onFailure((Throwable ex) -> {
            log.trace("end failed: {} {}", delegate.getMethod(), delegate.getURI(), ex);
            publishError(ex, handler);
        });
    }

    private <T> void publishError(Throwable exOrig, Handler<AsyncResult<T>> regularHandler) {
        assert exOrig != null : "exOrig != null";
        Throwable handler1Failure = null, handler2Failure = null;
        var exOrigAsFuture = Future.<T>failedFuture(exOrig);
        /* first give the WRITE handler a chance to do its job. */
        try {
            if (regularHandler != null) {
                regularHandler.handle(exOrigAsFuture);
                return; /* error successfully handled. Done. */
            }
        } catch (RuntimeException ex2) {
            handler1Failure = ex2; /* what a bad handler... */
        }
        /* then try the generic handler. */
        try {
            if (exceptionHandler != null) {
                exceptionHandler.handle(exOrig);
                return; /* error successfully handled. We're done. */
            }
        } catch (RuntimeException ex2) {
            handler2Failure = ex2; /* what a bad handler... */
        }
        /* No handler was able to handle the exception (either there was no
         * handler or it failed). So log it here as a last resort. */
        log.error("{}: {}", dbgHint, exOrig.getMessage(), log.isDebugEnabled() ? exOrig : null);
        /* Well... Some handlers seem unable to do their job. So also log why
         * they failed to handle the exception. */
        if (handler1Failure != null) {
            log.debug("{}: {}", handler1Failure.getMessage(), exceptionHandler.getClass(), handler1Failure);
        }
        if (handler2Failure != null) {
            log.debug("{}: {}", handler2Failure.getMessage(), exceptionHandler.getClass(), handler2Failure);
        }
    }

    @Override
    public Future<Void> write(Buffer data) {
        var p = Promise.<Void>promise();
        write_(data, p);
        return p.future();
    }

    @Override
    public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
        write_(data, handler);
    }

    @Override
    public void end(Buffer data, Handler<AsyncResult<Void>> handler) {
        var p = Promise.<Void>promise();
        end_(data, p);
        p.future().onComplete(handler);
    }

    @Override
    public Future<Void> end() {
        var p = Promise.<Void>promise();
        end_(null, p);
        return p.future();
    }

    @Override
    public Future<Void> end(Buffer data) {
        var p = Promise.<Void>promise();
        end_(data, p);
        return p.future();
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        var p = Promise.<Void>promise();
        end_(null, p);
        p.future().onComplete(handler);
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
