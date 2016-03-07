package org.swisspush.gateleen.core.storage;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.HashMap;
import java.util.Map;

/**
 * Mock for the ResourceStorage based on a HashMap
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class MockResourceStorage implements ResourceStorage {

    private Map<String, String> localStorageValues = new HashMap<>();

    public MockResourceStorage(){}

    public MockResourceStorage(Map<String, String> initalMockData){
        localStorageValues.putAll(initalMockData);
    }

    public void putMockData(String key, String value){
        localStorageValues.put(key, value);
    }

    @Override
    public void get(String path, Handler<Buffer> bodyHandler) {
        String result = localStorageValues.get(path);
        if(result != null) {
            bodyHandler.handle(Buffer.buffer(result));
        } else {
            bodyHandler.handle(null);
        }
    }

    @Override
    public void put(String uri, MultiMap headers, Buffer buffer, Handler<Integer> doneHandler) {
        localStorageValues.put(uri, buffer.toString());
        doneHandler.handle(StatusCode.OK.getStatusCode());
    }

    @Override
    public void put(String uri, Buffer buffer, Handler<Integer> doneHandler) {
        localStorageValues.put(uri, buffer.toString());
        doneHandler.handle(StatusCode.OK.getStatusCode());
    }

    @Override
    public void delete(String uri, Handler<Integer> doneHandler) {
        localStorageValues.remove(uri);
        doneHandler.handle(StatusCode.OK.getStatusCode());
    }
}
