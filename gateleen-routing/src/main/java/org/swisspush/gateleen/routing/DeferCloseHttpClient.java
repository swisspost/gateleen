package org.swisspush.gateleen.routing;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.streams.ReadStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;


/**
 * Decorates an {@link HttpClient} to only effectively close the client
 * if there are no more requests in progress.
 *
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
    public HttpClientRequest request(HttpMethod method, int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        logger.debug("({}:{}).request({}, \"{}\")", host, port, method, requestURI);
        countOfRequestsInProgress += 1;
        logger.debug("Pending request count: {}", countOfRequestsInProgress);

        // Delegate to the same method on the delegate. But install our own handler which
        // allows us to intercept the response.
        HttpClientRequest request = delegate.request(method, port, host, requestURI, upstreamRsp -> {
            logger.debug("onUpstreamRsp(code={})", upstreamRsp.statusCode());
            // 1st we have to pass-through the response so our caller is able to install its handlers.
            try {
                callHandlerIfExists(responseHandler, upstreamRsp);
            } catch (Exception e) {
                // Does not make sense to install any handlers. Just make sure we decrement
                // our counter then pass-through the exception.
                onEndOfRequestResponseCycle();
                throw e;
            }
            // We also need to ensure that our reference counter stays accurate. Badly vertx
            // may call BOTH of our handlers. And in this scenario we MUST NOT decrement
            // twice. So we additionally track this too.
            final AtomicBoolean needToDecrementCounter = new AtomicBoolean(true);
            // Then (after client installed its handlers), we now can intercept those by
            // replacing them with our own handlers.
            // To do this, we 1st backup the original handler (so we can delegate to it later).
            Handler<Void> originalEndHandler = getEndHandler(upstreamRsp);
            upstreamRsp.endHandler(event -> {
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
            Handler<Throwable> originalExceptionHandler = getExceptionHandler(upstreamRsp);
            upstreamRsp.exceptionHandler(event -> {
                logger.debug("upstreamRsp.exceptionHandler({})", event.toString());
                if (needToDecrementCounter.getAndSet(false)) {
                    onEndOfRequestResponseCycle();
                }
                callHandlerIfExists(originalExceptionHandler, event);
            });
        });
        return request;
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
    public void close() {
        if (countOfRequestsInProgress > 0) {
            logger.debug("Do NOT close right now. But close as soon there are no more pending requests (pending={})", countOfRequestsInProgress);
            doCloseWhenDone = true;
            // Still use a timer. Because who knows.
            vertx.setTimer(CLOSE_ANYWAY_AFTER_MS, timerId -> {
                if (doCloseWhenDone) {
                    logger.warn("RequestResponse cycle still running after {} seconds. Will close now to prevent resource leaks.", CLOSE_ANYWAY_AFTER_MS);
                    doCloseWhenDone = false;
                    delegate.close();
                }
            });
            return;
        }
        logger.debug("Client idle. Close right now");
        delegate.close();
    }


    ///////////////////////////////////////////////////////////////////////////////
    // Some helpers so we are able to do our work.
    ///////////////////////////////////////////////////////////////////////////////

    private <T> void callHandlerIfExists(Handler<T> maybeNull, T event){
        if(maybeNull == null){
            return; // No handler? Nothing we could call.
        }
        maybeNull.handle(event);
    }

    private Handler<Void> getEndHandler(HttpClientResponse rsp) {
        return getPrivateField(rsp, "endHandler", Handler.class);
    }

    private Handler<Throwable> getExceptionHandler(HttpClientResponse rsp) {
        return getPrivateField(rsp, "exceptionHandler", Handler.class);
    }

    private <T> T getPrivateField(HttpClientResponse rsp, String name, Class<T> type) {
        try {
            Field field = rsp.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(rsp);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////
    // Below are only the remaining methods which all just delegate.
    ///////////////////////////////////////////////////////////////////////////////
    //
    // TODO delete, just a handy vi macro.
    // jf(F wv$F)"ayj0Sreturn delegate.a;V:s:\((\|,\)[^,]\+ \([A-Za-z0-9_]\+\):\1 \2:g
    //  /@Override
    //

    @Override
    public HttpClientRequest request(HttpMethod method, RequestOptions options) {
        return delegate.request(method, options);
    }

    @Override
    public HttpClientRequest request(HttpMethod method, int port, String host, String requestURI) {
        return delegate.request(method, port, host, requestURI);
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String host, String requestURI) {
        return delegate.request(method, host, requestURI);
    }

    @Override
    public HttpClientRequest request(HttpMethod method, RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.request(method, options, responseHandler);
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.request(method, host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String requestURI) {
        return delegate.request(method, requestURI);
    }

    @Override
    public HttpClientRequest request(HttpMethod method, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.request(method, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest requestAbs(HttpMethod method, String absoluteURI) {
        return delegate.requestAbs(method, absoluteURI);
    }

    @Override
    public HttpClientRequest requestAbs(HttpMethod method, String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.requestAbs(method, absoluteURI, responseHandler);
    }

    @Override
    public HttpClientRequest get(RequestOptions options) {
        return delegate.get(options);
    }

    @Override
    public HttpClientRequest get(int port, String host, String requestURI) {
        return delegate.get(port, host, requestURI);
    }

    @Override
    public HttpClientRequest get(String host, String requestURI) {
        return delegate.get(host, requestURI);
    }

    @Override
    public HttpClientRequest get(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.get(options, responseHandler);
    }

    @Override
    public HttpClientRequest get(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.get(port, host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest get(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.get(host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest get(String requestURI) {
        return delegate.get(requestURI);
    }

    @Override
    public HttpClientRequest get(String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.get(requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest getAbs(String absoluteURI) {
        return delegate.getAbs(absoluteURI);
    }

    @Override
    public HttpClientRequest getAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.getAbs(absoluteURI, responseHandler);
    }

    @Override
    public HttpClient getNow(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.getNow(options, responseHandler);
    }

    @Override
    public HttpClient getNow(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.getNow(port, host, requestURI, responseHandler);
    }

    @Override
    public HttpClient getNow(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.getNow(host, requestURI, responseHandler);
    }

    @Override
    public HttpClient getNow(String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.getNow(requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest post(RequestOptions options) {
        return delegate.post(options);
    }

    @Override
    public HttpClientRequest post(int port, String host, String requestURI) {
        return delegate.post(port, host, requestURI);
    }

    @Override
    public HttpClientRequest post(String host, String requestURI) {
        return delegate.post(host, requestURI);
    }

    @Override
    public HttpClientRequest post(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.post(options, responseHandler);
    }

    @Override
    public HttpClientRequest post(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.post(port, host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest post(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.post(host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest post(String requestURI) {
        return delegate.post(requestURI);
    }

    @Override
    public HttpClientRequest post(String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.post(requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest postAbs(String absoluteURI) {
        return delegate.postAbs(absoluteURI);
    }

    @Override
    public HttpClientRequest postAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.postAbs(absoluteURI, responseHandler);
    }

    @Override
    public HttpClientRequest head(RequestOptions options) {
        return delegate.head(options);
    }

    @Override
    public HttpClientRequest head(int port, String host, String requestURI) {
        return delegate.head(port, host, requestURI);
    }

    @Override
    public HttpClientRequest head(String host, String requestURI) {
        return delegate.head(host, requestURI);
    }

    @Override
    public HttpClientRequest head(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.head(options, responseHandler);
    }

    @Override
    public HttpClientRequest head(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.head(port, host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest head(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.head(host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest head(String requestURI) {
        return delegate.head(requestURI);
    }

    @Override
    public HttpClientRequest head(String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.head(requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest headAbs(String absoluteURI) {
        return delegate.headAbs(absoluteURI);
    }

    @Override
    public HttpClientRequest headAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.headAbs(absoluteURI, responseHandler);
    }

    @Override
    public HttpClient headNow(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.headNow(options, responseHandler);
    }

    @Override
    public HttpClient headNow(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.headNow(port, host, requestURI, responseHandler);
    }

    @Override
    public HttpClient headNow(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.headNow(host, requestURI, responseHandler);
    }

    @Override
    public HttpClient headNow(String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.headNow(requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest options(RequestOptions options) {
        return delegate.options(options);
    }

    @Override
    public HttpClientRequest options(int port, String host, String requestURI) {
        return delegate.options(port, host, requestURI);
    }

    @Override
    public HttpClientRequest options(String host, String requestURI) {
        return delegate.options(host, requestURI);
    }

    @Override
    public HttpClientRequest options(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.options(options, responseHandler);
    }

    @Override
    public HttpClientRequest options(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.options(port, host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest options(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.options(host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest options(String requestURI) {
        return delegate.options(requestURI);
    }

    @Override
    public HttpClientRequest options(String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.options(requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest optionsAbs(String absoluteURI) {
        return delegate.optionsAbs(absoluteURI);
    }

    @Override
    public HttpClientRequest optionsAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.optionsAbs(absoluteURI, responseHandler);
    }

    @Override
    public HttpClient optionsNow(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.optionsNow(options, responseHandler);
    }

    @Override
    public HttpClient optionsNow(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.optionsNow(port, host, requestURI, responseHandler);
    }

    @Override
    public HttpClient optionsNow(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.optionsNow(host, requestURI, responseHandler);
    }

    @Override
    public HttpClient optionsNow(String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.optionsNow(requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest put(RequestOptions options) {
        return delegate.put(options);
    }

    @Override
    public HttpClientRequest put(int port, String host, String requestURI) {
        return delegate.put(port, host, requestURI);
    }

    @Override
    public HttpClientRequest put(String host, String requestURI) {
        return delegate.put(host, requestURI);
    }

    @Override
    public HttpClientRequest put(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.put(options, responseHandler);
    }

    @Override
    public HttpClientRequest put(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.put(port, host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest put(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.put(host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest put(String requestURI) {
        return delegate.put(requestURI);
    }

    @Override
    public HttpClientRequest put(String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.put(requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest putAbs(String absoluteURI) {
        return delegate.putAbs(absoluteURI);
    }

    @Override
    public HttpClientRequest putAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.putAbs(absoluteURI, responseHandler);
    }

    @Override
    public HttpClientRequest delete(RequestOptions options) {
        return delegate.delete(options);
    }

    @Override
    public HttpClientRequest delete(int port, String host, String requestURI) {
        return delegate.delete(port, host, requestURI);
    }

    @Override
    public HttpClientRequest delete(String host, String requestURI) {
        return delegate.delete(host, requestURI);
    }

    @Override
    public HttpClientRequest delete(RequestOptions options, Handler<HttpClientResponse> responseHandler) {
        return delegate.delete(options, responseHandler);
    }

    @Override
    public HttpClientRequest delete(int port, String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.delete(port, host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest delete(String host, String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.delete(host, requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest delete(String requestURI) {
        return delegate.delete(requestURI);
    }

    @Override
    public HttpClientRequest delete(String requestURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.delete(requestURI, responseHandler);
    }

    @Override
    public HttpClientRequest deleteAbs(String absoluteURI) {
        return delegate.deleteAbs(absoluteURI);
    }

    @Override
    public HttpClientRequest deleteAbs(String absoluteURI, Handler<HttpClientResponse> responseHandler) {
        return delegate.deleteAbs(absoluteURI, responseHandler);
    }

    @Override
    public HttpClient websocket(RequestOptions options, Handler<WebSocket> wsConnect) {
        return delegate.websocket(options, wsConnect);
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, Handler<WebSocket> wsConnect) {
        return delegate.websocket(port, host, requestURI, wsConnect);
    }

    @Override
    public HttpClient websocket(RequestOptions options, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(options, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(port, host, requestURI, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(String host, String requestURI, Handler<WebSocket> wsConnect) {
        return delegate.websocket(host, requestURI, wsConnect);
    }

    @Override
    public HttpClient websocket(String host, String requestURI, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(host, requestURI, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, Handler<WebSocket> wsConnect) {
        return delegate.websocket(options, headers, wsConnect);
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect) {
        return delegate.websocket(port, host, requestURI, headers, wsConnect);
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(options, headers, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(port, host, requestURI, headers, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect) {
        return delegate.websocket(host, requestURI, headers, wsConnect);
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(host, requestURI, headers, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        return delegate.websocket(options, headers, version, wsConnect);
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        return delegate.websocket(port, host, requestURI, headers, version, wsConnect);
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(options, headers, version, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(port, host, requestURI, headers, version, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        return delegate.websocket(host, requestURI, headers, version, wsConnect);
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(host, requestURI, headers, version, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        return delegate.websocket(options, headers, version, subProtocols, wsConnect);
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        return delegate.websocket(port, host, requestURI, headers, version, subProtocols, wsConnect);
    }

    @Override
    public HttpClient websocketAbs(String url, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocketAbs(url, headers, version, subProtocols, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(RequestOptions options, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(options, headers, version, subProtocols, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(port, host, requestURI, headers, version, subProtocols, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        return delegate.websocket(host, requestURI, headers, version, subProtocols, wsConnect);
    }

    @Override
    public HttpClient websocket(String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(host, requestURI, headers, version, subProtocols, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(String requestURI, Handler<WebSocket> wsConnect) {
        return delegate.websocket(requestURI, wsConnect);
    }

    @Override
    public HttpClient websocket(String requestURI, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(requestURI, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, Handler<WebSocket> wsConnect) {
        return delegate.websocket(requestURI, headers, wsConnect);
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(requestURI, headers, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect) {
        return delegate.websocket(requestURI, headers, version, wsConnect);
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(requestURI, headers, version, wsConnect, failureHandler);
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect) {
        return delegate.websocket(requestURI, headers, version, subProtocols, wsConnect);
    }

    @Override
    public HttpClient websocket(String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols, Handler<WebSocket> wsConnect, Handler<Throwable> failureHandler) {
        return delegate.websocket(requestURI, headers, version, subProtocols, wsConnect, failureHandler);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(RequestOptions options) {
        return delegate.websocketStream(options);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(int port, String host, String requestURI) {
        return delegate.websocketStream(port, host, requestURI);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String host, String requestURI) {
        return delegate.websocketStream(host, requestURI);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(RequestOptions options, MultiMap headers) {
        return delegate.websocketStream(options, headers);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(int port, String host, String requestURI, MultiMap headers) {
        return delegate.websocketStream(port, host, requestURI, headers);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String host, String requestURI, MultiMap headers) {
        return delegate.websocketStream(host, requestURI, headers);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(RequestOptions options, MultiMap headers, WebsocketVersion version) {
        return delegate.websocketStream(options, headers, version);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version) {
        return delegate.websocketStream(port, host, requestURI, headers, version);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String host, String requestURI, MultiMap headers, WebsocketVersion version) {
        return delegate.websocketStream(host, requestURI, headers, version);
    }

    @Override
    public ReadStream<WebSocket> websocketStreamAbs(String url, MultiMap headers, WebsocketVersion version, String subProtocols) {
        return delegate.websocketStreamAbs(url, headers, version, subProtocols);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(RequestOptions options, MultiMap headers, WebsocketVersion version, String subProtocols) {
        return delegate.websocketStream(options, headers, version, subProtocols);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(int port, String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols) {
        return delegate.websocketStream(port, host, requestURI, headers, version, subProtocols);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String host, String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols) {
        return delegate.websocketStream(host, requestURI, headers, version, subProtocols);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String requestURI) {
        return delegate.websocketStream(requestURI);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String requestURI, MultiMap headers) {
        return delegate.websocketStream(requestURI, headers);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String requestURI, MultiMap headers, WebsocketVersion version) {
        return delegate.websocketStream(requestURI, headers, version);
    }

    @Override
    public ReadStream<WebSocket> websocketStream(String requestURI, MultiMap headers, WebsocketVersion version, String subProtocols) {
        return delegate.websocketStream(requestURI, headers, version, subProtocols);
    }

    @Override
    public HttpClient connectionHandler(Handler<HttpConnection> handler) {
        return delegate.connectionHandler(handler);
    }

    @Override
    public HttpClient redirectHandler(Function<HttpClientResponse, Future<HttpClientRequest>> handler) {
        return delegate.redirectHandler(handler);
    }

    @Override
    public Function<HttpClientResponse, Future<HttpClientRequest>> redirectHandler() {
        return delegate.redirectHandler();
    }

}
