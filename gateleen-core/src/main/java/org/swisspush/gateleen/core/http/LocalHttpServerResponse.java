package org.swisspush.gateleen.core.http;

import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.NetSocket;

import java.util.List;

/**
 * Bridges the reponses of a LocalHttpClientRequest.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class LocalHttpServerResponse extends BufferBridge implements HttpServerResponse {

    private int statusCode;
    private String statusMessage;
    private static final String EMPTY = "";
    private Handler<HttpClientResponse> responseHandler;
    private MultiMap headers = new CaseInsensitiveHeaders();
    private boolean bound = false;

    private HttpClientResponse clientResponse = new HttpClientResponse() {
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
        public String getTrailer(String trailerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultiMap trailers() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> cookies() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpClientResponse bodyHandler(final Handler<Buffer> bodyHandler) {
            final Buffer body = Buffer.buffer();
            handler(body::appendBuffer);
            endHandler(new VoidHandler() {
                public void handle() {
                    bodyHandler.handle(body);
                }
            });
            return this;
        }

        @Override
        public NetSocket netSocket() {
            throw new UnsupportedOperationException();
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
        return this;
    }

    @Override
    public boolean isChunked() {
        return false;
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
    public MultiMap trailers() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putTrailer(String name, String value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, CharSequence value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putTrailer(String name, Iterable<String> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, Iterable<CharSequence> value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse closeHandler(Handler<Void> handler) {
        return this;
    }

    @Override
    public HttpServerResponse write(Buffer chunk) {
        ensureBound();
        doWrite(chunk);
        return this;
    }

    private void ensureBound() {
        if(!bound) {
            bound=true;
            if(statusCode == 0) {
                statusCode = 200;
                statusMessage = "OK";
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
    public HttpServerResponse writeContinue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void end(String chunk) {
        write(chunk);
        end();
    }

    @Override
    public void end(String chunk, String enc) {
        write(chunk, enc);
        end();
    }

    @Override
    public void end(Buffer chunk) {
        write(chunk);
        end();
    }

    @Override
    public void end() {
        ensureBound();
        doEnd();
    }

    @Override
    public HttpServerResponse sendFile(String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse sendFile(String filename, long offset, long length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse sendFile(String filename, Handler<AsyncResult<Void>> resultHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse sendFile(String filename, long offset, long length, Handler<AsyncResult<Void>> resultHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {

    }

    @Override
    public boolean ended() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean closed() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean headWritten() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse headersEndHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse bodyEndHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long bytesWritten() {
        throw new UnsupportedOperationException();
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
    public HttpServerResponse drainHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpServerResponse exceptionHandler(Handler<Throwable> handler) {
        setExceptionHandler(handler);
        return this;
    }
}
