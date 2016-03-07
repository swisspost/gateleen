package org.swisspush.gateleen.packing;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;

/**
 * Extract requests from a packing request and forward them to a handler.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class PackingHandler implements Handler<Buffer> {

    private HttpServerRequest request;
    private Handler<Buffer> nextHandler;

    public static final String PACK_HEADER = "X-Pack-Size";

    public static boolean isPacked(HttpServerRequest request) {
        return request.headers().get(PACK_HEADER) != null;
    }

    /**
     * @param request request
     * @param nextHandler the handle to forward the unpacked
     */
    public PackingHandler(HttpServerRequest request, Handler<Buffer> nextHandler) {
        super();
        this.request = request;
        this.nextHandler = nextHandler;
    }

    @Override
    public void handle(Buffer event) {
        request.bodyHandler(event1 -> nextHandler.handle(event1));
    }
}
