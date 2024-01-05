package org.swisspush.gateleen.core.storage;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public interface ResourceStorage {
    void get(String path, Handler<Buffer> bodyHandler);

    /**
     * <p>Under normal operation 'doneHandler' is called with the result. BUT: There are some impl
     * which sometimes call it with 'null' as its value. It looks like this indicates that there was
     * some kind of error. Most of the time impl knows exactly what the error was. But there is no
     * way that it would report it to the caller (because this API does not provide a way to do so).</p>
     *
     * <p>There is yet another special case: Some impls in some cases do NOT call 'doneHandler' at
     * all. So there is no way to know what happened behind the scene.</p>
     */
    void put(String uri, MultiMap headers, Buffer buffer, Handler<Integer> doneHandler);

    void put(String uri, Buffer buffer, Handler<Integer> doneHandler);

    void delete(String uri, Handler<Integer> doneHandler);
}
