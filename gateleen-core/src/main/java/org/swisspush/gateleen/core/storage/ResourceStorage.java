package org.swisspush.gateleen.core.storage;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public interface ResourceStorage {
    void get(String path, Handler<Buffer> bodyHandler);

    void put(String uri, MultiMap headers, Buffer buffer, Handler<Integer> doneHandler);

    void put(String uri, Buffer buffer, Handler<Integer> doneHandler);

    void delete(String uri, Handler<Integer> doneHandler);
}
