package org.swisspush.gateleen.routing;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.vertx.core.Future.succeededFuture;


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
public class AutomaticChunkedRequestTransfer extends AbstractChunkedTransfer<HttpClientRequest> {

    private final Vertx vertx;
    private final AtomicBoolean firstBuffer = new AtomicBoolean(true);

    AutomaticChunkedRequestTransfer(Vertx vertx, HttpClientRequest delegate, String dbgHint) {
        super(delegate, dbgHint);
        assert vertx != null : "vertx != null";
        this.vertx = vertx;
    }

    @Override
    protected void setDelegateChunked() {
        // avoid multiple calls due to a 'syncronized' block in HttpClient's implementation
        delegate.setChunked(true);
    }

    @Override
    protected boolean isDelegateChunked() {
        return delegate.isChunked();
    }

    private void write_(Buffer data, Handler<AsyncResult<Void>> handler) {
        /* only now we know for sure that there IS a body. */
        Future.<Void>succeededFuture().<Void>compose((Void nil) -> {
            if (firstBuffer.getAndSet(false)) {
                enableChunkedTransfer();
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

}
