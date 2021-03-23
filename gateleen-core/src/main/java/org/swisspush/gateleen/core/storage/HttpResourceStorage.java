package org.swisspush.gateleen.core.storage;

import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives programmatic access to the resource storage.
 * 
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class HttpResourceStorage implements ResourceStorage {

    private String host;
    private int port;
    private HttpClient client;

    private Logger log = LoggerFactory.getLogger(HttpResourceStorage.class);

    private static final long TIMEOUT = 30000;

    public HttpResourceStorage(Vertx vertx) {
        this(vertx, "localhost", 8989);
    }

    public HttpResourceStorage(Vertx vertx, String host, int port) {
        this.host = host;
        this.port = port;
        this.client = vertx.createHttpClient(new HttpClientOptions()
                .setDefaultHost(host)
                .setDefaultPort(port)
                .setMaxPoolSize(500)
                .setReuseAddress(true)
                .setKeepAlive(true)
                .setPipelining(false));
    }

    @Override
    public void get(final String path, final Handler<Buffer> bodyHandler) {
        log.debug("Reading {}", path);
        HttpClientRequest request = client.get(path, response -> {
            response.exceptionHandler(exception -> {
                log.error("Reading {} failed: {}", path, exception.getMessage());
                bodyHandler.handle(null);
            });
            if (response.statusCode() == StatusCode.OK.getStatusCode()) {
                response.bodyHandler(bodyHandler);
            } else {
                log.debug("Got status code other than 200. Status code = {}, status message is '{}'.",
                        (response == null ? "<null>" : response.statusCode()),
                        ((response == null || response.statusMessage() == null) ? "<null>" : response.statusMessage()));
                bodyHandler.handle(null);
            }
        });
        request.exceptionHandler(e -> {
            log.error("Storage request error", e);
            bodyHandler.handle(null);
        });
        request.setTimeout(TIMEOUT);
        request.end();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public void put(final String uri, MultiMap headers, Buffer buffer, final Handler<Integer> doneHandler) {

        HttpClientRequest request = client.put(uri, response -> {
            response.exceptionHandler(exception -> {
                log.error("Exception on response to PUT from {}: {}", uri, exception.getMessage());
                doneHandler.handle(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            });
            response.endHandler(event -> doneHandler.handle(response.statusCode()));
        });
        request.exceptionHandler(exception -> {
            log.error("Putting {} failed: {}", uri, exception.getMessage());
            doneHandler.handle(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
        });

        if (headers != null) {
            // headers from the original request (containing x-expire-after time)
            request.headers().addAll(headers);
        }

        request.setTimeout(TIMEOUT);
        request.putHeader("Content-Length", "" + buffer.length());
        request.write(buffer);
        request.end();
    }

    @Override
    public void put(final String uri, final Buffer buffer, final Handler<Integer> doneHandler) {
        put(uri, null, buffer, doneHandler);
    }

    @Override
    public void delete(final String uri, final Handler<Integer> doneHandler) {
        HttpClientRequest request = client.delete(uri, response -> {
            response.exceptionHandler(exception -> {
                log.error("Exception on response to DELETE from {}: {}", uri, exception.getMessage());
                doneHandler.handle(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            });
            response.endHandler(event -> doneHandler.handle(response.statusCode()));
        });
        request.exceptionHandler(exception -> {
            log.error("Deleting {} failed: {}", uri, exception.getMessage());
            doneHandler.handle(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
        });
        request.setTimeout(TIMEOUT);
        request.end();
    }

}
