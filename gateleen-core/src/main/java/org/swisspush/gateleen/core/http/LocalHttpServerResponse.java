package org.swisspush.gateleen.core.http;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.headers.VertxHttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StatusCode;

/**
 * Bridges the reponses of a LocalHttpClientRequest.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class LocalHttpServerResponse extends BufferBridge implements FastFailHttpServerResponse {

    private static final Logger logger = LoggerFactory.getLogger(LocalHttpServerResponse.class);
    private int statusCode;
    private String statusMessage;
    private static final String EMPTY = "";
    private Handler<HttpClientResponse> responseHandler;
    private MultiMap headers = new VertxHttpHeaders();
    private boolean chunked = false;
    private boolean bound = false;
    private boolean closed = false;
    private boolean written = false;

    private HttpClientResponse clientResponse = new FastFaiHttpClientResponse () {
        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public String statusMessage() {
            if(statusMessage == null){
                StatusCode code = StatusCode.fromCode(statusCode());
                if(code != null){
                    statusMessage = code.getStatusMessage();
                } else {
                    statusMessage = EMPTY;
                }
            }
            return statusMessage;
        }

        @Override
        public MultiMap headers() {
            return headers;
        }

        @Override
        public String getHeader(String headerName) {
            return headers().get(headerName);
        }

        @Override
        public String getHeader(CharSequence headerName) {
            return headers().get(headerName);
        }

        @Override
        public HttpClientResponse bodyHandler(final Handler<Buffer> bodyHandler) {
            final Buffer body = Buffer.buffer();
            handler(body::appendBuffer);
            endHandler(aVoid -> bodyHandler.handle(body));
            return this;
        }

        @Override
        public HttpClientResponse customFrameHandler(Handler<HttpFrame> handler) { return this; }

        @Override
        public HttpClientRequest request() {
            return null;
        }

        @Override
        public HttpClientResponse endHandler(Handler<Void> handler) {
            setEndHandler(handler);
            return this;
        }

        @Override
        public HttpClientResponse handler(Handler<Buffer> handler) {
            setDataHandler(handler);
            // As soon as the dataHandler is set, we can dump the queue in it.
            pump();
            return this;
        }

        @Override
        public HttpClientResponse pause() {
            return this;
        }

        @Override
        public HttpClientResponse resume() {
            return this;
        }

        @Override
        public HttpClientResponse exceptionHandler(Handler<Throwable> handler) {
            return this;
        }
    };

    public LocalHttpServerResponse(Vertx vertx, Handler<HttpClientResponse> responseHandler) {
        super(vertx);
        // Attach most simple possible exception handler to base.
        setExceptionHandler(thr -> {
            logger.error("Processing of response failed.", thr);
        });
        this.responseHandler = responseHandler;
    }

    @Override
    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public HttpServerResponse setStatusCode(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    @Override
    public String getStatusMessage() {
        if(statusMessage == null){
            StatusCode code = StatusCode.fromCode(getStatusCode());
            if(code != null){
                statusMessage = code.getStatusMessage();
            } else {
                statusMessage = EMPTY;
            }
        }
        return statusMessage;
    }

    @Override
    public HttpServerResponse setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
        return this;
    }

    @Override
    public HttpServerResponse setChunked(boolean chunked) {
        // Note that we don't really need to distinguish between 'chunked' of 'content-length' as we all make in-memory without network
        // Though, as this response can go though e.g. Gateleen's Forwarder, we must help it to construct a syntactially correct http-Response
        this.chunked = chunked;
        return this;
    }

    @Override
    public boolean isChunked() {
        return chunked;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public HttpServerResponse putHeader(String name, String value) {
        headers().set(name, value);
        return this;
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, CharSequence value) {
        headers().set(name, value);
        return this;
    }

    @Override
    public HttpServerResponse putHeader(String name, Iterable<String> values) {
        for(String value : values) {
            headers().add(name, value);
        }
        return this;
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, Iterable<CharSequence> values) {
        for(CharSequence value : values) {
            headers().add(name, value);
        }
        return this;
    }

    @Override
    public HttpServerResponse closeHandler(Handler<Void> handler) {
        return this;
    }

    @Override
    public HttpServerResponse write(Buffer chunk) {
        // emulate Vertx's HttpServerResponseImpl
        if (!chunked && !headers.contains(HttpHeaders.CONTENT_LENGTH)) {
            IllegalStateException ex = new IllegalStateException("You must set the Content-Length header to be the total size of the message "
                    + "body BEFORE sending any data if you are not using HTTP chunked encoding.");
            logger.error("non-proper HttpServerResponse occured", ex);
            throw ex;
        }
        ensureBound();
        doWrite(chunk);
        return this;
    }

    private void ensureBound() {
        if (!bound) {
            bound = true;
            if (statusCode == 0) {
                statusCode = 200;
                statusMessage = "OK";
            }
            if (chunked) {
                headers.set(HttpHeaders.TRANSFER_ENCODING, HttpHeaders.CHUNKED);
            }
            responseHandler.handle(clientResponse);
        }
    }

    @Override
    public HttpServerResponse write(String chunk, String enc) {
        write(Buffer.buffer(chunk, enc));
        return this;
    }

    @Override
    public HttpServerResponse write(String chunk) {
        write(Buffer.buffer(chunk));
        return this;
    }

    @Override
    public void end(String chunk) {
        end(Buffer.buffer(chunk));
    }

    @Override
    public void end(String chunk, String enc) {
        end(Buffer.buffer(chunk, enc));
    }

    @Override
    public void end(Buffer chunk) {
        if (!bound) {
            // this is a call to 'end(...)' without calling any 'write(...)' before
            // in this case it is allows to _not_ setChunked(true) and _not_ set a content-length header.
            // we will then set the content-length header automatically to the size of this one-and-only buffer
            if (!chunked && !headers.contains(HttpHeaders.CONTENT_LENGTH)) {
                headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(chunk.length()));
            }
        }
        write(chunk);
        end();
    }

    @Override
    public void end() {
        written = true;
        ensureBound();
        doEnd();
    }

    @Override
    public void close() {
        // makes not really sense as we have no underlying TCP connection which can be closed
        // nevertheless we simulate the behaviour of a 'real' HttpServerResponse
        closed = true;
    }

    @Override
    public boolean ended() {
        return written;
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public boolean headWritten() {
//        // we have no stream
//        // --> headers can be manipulated even when we already have written body bytes
//        // but when the last body bytes where written, then we 'simulate' that the headers are also written (and therefore should not be manipulated any more)
        return written;
    }

    @Override
    public HttpServerResponse setWriteQueueMaxSize(int maxSize) {
        return this;
    }

    @Override
    public boolean writeQueueFull() {
        return false;
    }

    @Override
    public HttpServerResponse exceptionHandler(Handler<Throwable> handler) {
        setExceptionHandler(handler);
        return this;
    }
}
