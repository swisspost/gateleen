package org.swisspush.gateleen.routing;

import io.vertx.core.*;
import io.vertx.core.http.*;
import io.vertx.core.net.SSLOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;


/**
 * Decorates an {@link HttpClient} to only effectively close the client
 * if there are no more requests in progress.
 * <p>
 * HINT: We for now only fix the issue in the exact call we know to misbehave
 * in our concrete scenario. Feel free to implement the (few...) remaining
 * methods.
 */
public class DeferCloseHttpClient implements HttpClient {

    private final int CLOSE_ANYWAY_AFTER_MS = 86_400_000; // <- TODO: Find a good value.
    private static final Logger logger = LoggerFactory.getLogger(DeferCloseHttpClient.class);
    private final Vertx vertx;
    private final HttpClient delegate;
    private int countOfRequestsInProgress = 0;
    private boolean doCloseWhenDone = false;

    /**
     * See {@link DeferCloseHttpClient}.
     */
    public DeferCloseHttpClient(Vertx vertx, HttpClient delegate) {
        this.vertx = vertx;
        this.delegate = delegate;
    }


    @Override
    public void request(HttpMethod method, int port, String host, String requestURI, Handler<AsyncResult<HttpClientRequest>> handler) {
        logger.debug("({}:{}).request({}, \"{}\")", host, port, method, requestURI);
        countOfRequestsInProgress += 1;
        logger.debug("Pending request count: {}", countOfRequestsInProgress);
        delegate.request(method, port, host, requestURI).onComplete(asyncRequestResult -> {
            if (asyncRequestResult.failed()) {
                logger.debug("({}:{}).request({}, \"{}\") failed in request() with {}", host, port, method, requestURI, asyncRequestResult.cause());
                // do the same as further down
                onEndOfRequestResponseCycle();
                return;
            }
            HttpClientRequest request = asyncRequestResult.result();
            request.response(asyncResponseResult -> {
                if (asyncResponseResult.failed()) {
                    logger.debug("({}:{}).request({}, \"{}\") failed in response() with {}", host, port, method, requestURI, asyncResponseResult.cause());
                    // Does not make sense to install any handlers. Just make sure we decrement
                    // our counter then pass-through the exception.
                    onEndOfRequestResponseCycle();
                    return;
                }
                onUpstreamResponse(asyncResponseResult.result());
            });
        }).onComplete(handler);
    }

    private void onUpstreamResponse(HttpClientResponse rsp) {
        // Delegate to the same method on the delegate. But install our own handler which
        // allows us to intercept the response.
        logger.debug("onUpstreamRsp(code={})", rsp.statusCode());
        // 1st we have to pass-through the response so our caller is able to install its handlers.

        // We also need to ensure that our reference counter stays accurate. Badly vertx
        // may call BOTH of our handlers. And in this scenario we MUST NOT decrement
        // twice. So we additionally track this too.
        final AtomicBoolean needToDecrementCounter = new AtomicBoolean(true);
        // Then (after client installed its handlers), we now can intercept those by
        // replacing them with our own handlers.
        // To do this, we 1st backup the original handler (so we can delegate to it later).
        Handler<Void> originalEndHandler = getEndHandler(rsp);
        rsp.endHandler(event -> {
            logger.debug("upstreamRsp.endHandler()");
            if (needToDecrementCounter.getAndSet(false)) {
                onEndOfRequestResponseCycle();
            }
            // Call the original handler independent of the above condition to not change
            // behaviour of the impl we are decorating.
            callHandlerIfExists(originalEndHandler, event);
        });
        // We also need to intercept exception handler to decrement our counter in case
        // of erroneous-end scenario. Basically same idea as above.
        Handler<Throwable> originalExceptionHandler = getExceptionHandler(rsp);
        rsp.exceptionHandler(event -> {
            logger.debug("upstreamRsp.exceptionHandler({})", event.toString());
            if (needToDecrementCounter.getAndSet(false)) {
                onEndOfRequestResponseCycle();
            }
            callHandlerIfExists(originalExceptionHandler, event);
        });
    }

    private void onEndOfRequestResponseCycle() {
        countOfRequestsInProgress -= 1;
        logger.debug("Pending request count: {}", countOfRequestsInProgress);
        if (countOfRequestsInProgress == 0 && doCloseWhenDone) {
            logger.debug("No pending request right now. And someone called 'close()' earlier. So close now.");
            doCloseWhenDone = false;
            try {
                delegate.close();
            } catch (Exception e) {
                logger.warn("delegate.close() failed", e);
            }
        }
    }

    @Override
    public Future<Void> close() {
        if (countOfRequestsInProgress > 0) {
            logger.debug("Do NOT close right now. But close as soon there are no more pending requests (pending={})", countOfRequestsInProgress);
            doCloseWhenDone = true;
            // Still use a timer. Because who knows.
            vertx.setTimer(CLOSE_ANYWAY_AFTER_MS, timerId -> {
                if (doCloseWhenDone) {
                    logger.warn("RequestResponse cycle still running after {} ms. Will close now to prevent resource leaks.", CLOSE_ANYWAY_AFTER_MS);
                    doCloseWhenDone = false;
                    delegate.close();
                }
            });
            return Future.succeededFuture();
        }
        logger.debug("Client idle. Close right now");
        delegate.close();
        return Future.succeededFuture();
    }

    ///////////////////////////////////////////////////////////////////////////////
    // Some helpers so we are able to do our work.
    ///////////////////////////////////////////////////////////////////////////////

    private <T> void callHandlerIfExists(Handler<T> maybeNull, T event) {
        if (maybeNull == null) {
            return; // No handler? Nothing we could call.
        }
        maybeNull.handle(event);
    }

    private Handler<Void> getEndHandler(HttpClientResponse rsp) {
        return getPrivateField(rsp, "endHandler");
    }

    private Handler<Throwable> getExceptionHandler(HttpClientResponse rsp) {
        return getPrivateField(rsp, "exceptionHandler");
    }

    private <T> T getPrivateField(HttpClientResponse rsp, String name) {
        try {
            Field eventHandlerField = rsp.getClass().getDeclaredField("eventHandler");
            eventHandlerField.setAccessible(true);
            Object eventHanlderObj = eventHandlerField.get(rsp);
            if (eventHanlderObj == null) return null;
            Field handlerField = eventHanlderObj.getClass().getDeclaredField(name);
            handlerField.setAccessible(true);
            return (T) handlerField.get(eventHanlderObj);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////
    // Below are only the remaining methods which all just delegate.
    ///////////////////////////////////////////////////////////////////////////////
    @Override
    public Future<HttpClientRequest> request(RequestOptions options) {
        return delegate.request(options);
    }


    @Override
    public void request(RequestOptions options, Handler<AsyncResult<HttpClientRequest>> handler) {
        delegate.request(options, handler);
    }

    @Override
    public Future<HttpClientRequest> request(HttpMethod method, int port, String host, String requestURI) {
        return delegate.request(method, port, host, requestURI);
    }

    @Override
    public void request(HttpMethod method, String host, String requestURI, Handler<AsyncResult<HttpClientRequest>> handler) {
        delegate.request(method, host, requestURI, handler);
    }

    @Override
    public Future<HttpClientRequest> request(HttpMethod method, String host, String requestURI) {
        return delegate.request(method, host, requestURI);
    }

    @Override
    public void request(HttpMethod method, String requestURI, Handler<AsyncResult<HttpClientRequest>> handler) {
        delegate.request(method, requestURI, handler);
    }

    @Override
    public Future<HttpClientRequest> request(HttpMethod method, String requestURI) {
        return delegate.request(method, requestURI);
    }

    @Override
    public void webSocket(int port, String host, String requestURI, Handler<AsyncResult<WebSocket>> handler) {
        delegate.webSocket(port, host, requestURI, handler);
    }

    @Override
    public Future<WebSocket> webSocket(int port, String host, String requestURI) {
        return delegate.webSocket(port, host, requestURI);
    }

    @Override
    public void webSocket(String host, String requestURI, Handler<AsyncResult<WebSocket>> handler) {
        delegate.webSocket(host, requestURI, handler);
    }

    @Override
    public Future<WebSocket> webSocket(String host, String requestURI) {
        return delegate.webSocket(host, requestURI);
    }

    @Override
    public void webSocket(String requestURI, Handler<AsyncResult<WebSocket>> handler) {
        delegate.webSocket(requestURI, handler);
    }

    @Override
    public Future<WebSocket> webSocket(String requestURI) {
        return delegate.webSocket(requestURI);
    }

    @Override
    public void webSocket(WebSocketConnectOptions options, Handler<AsyncResult<WebSocket>> handler) {
        delegate.webSocket(options, handler);
    }

    @Override
    public Future<WebSocket> webSocket(WebSocketConnectOptions options) {
        return delegate.webSocket(options);
    }

    @Override
    public void webSocketAbs(String url, MultiMap headers, WebsocketVersion version, List<String> subProtocols, Handler<AsyncResult<WebSocket>> handler) {
        delegate.webSocketAbs(url, headers, version, subProtocols, handler);
    }

    @Override
    public Future<WebSocket> webSocketAbs(String url, MultiMap headers, WebsocketVersion version, List<String> subProtocols) {
        return delegate.webSocketAbs(url, headers, version, subProtocols);
    }

    @Override
    public Future<Boolean> updateSSLOptions(SSLOptions options, boolean force) {
        return delegate.updateSSLOptions(options, force);
    }

    @Override
    public HttpClient connectionHandler(Handler<HttpConnection> handler) {
        return delegate.connectionHandler(handler);
    }

    @Override
    public HttpClient redirectHandler(Function<HttpClientResponse, Future<RequestOptions>> handler) {
        return delegate.redirectHandler(handler);
    }

    @Override
    public Function<HttpClientResponse, Future<RequestOptions>> redirectHandler() {
        return delegate.redirectHandler();
    }

    @Override
    public void close(Handler<AsyncResult<Void>> handler) {
        delegate.close(handler);
    }
}
