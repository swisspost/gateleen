package org.swisspush.gateleen.validation.validation.mocks;

import org.swisspush.gateleen.core.storage.ResourceStorage;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

import java.util.HashMap;
import java.util.Map;

/**
 * Mock for the ResourceStorage based on a HashMap
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ResourceStorageMock implements ResourceStorage {

    private Map<String, String> localStorageValues = new HashMap<>();;

    public ResourceStorageMock(){}

    public ResourceStorageMock(Map<String, String> initalMockData){
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

    }

    @Override
    public void put(String uri, Buffer buffer, Handler<Integer> doneHandler) {
        localStorageValues.put(uri, buffer.toString());
        doneHandler.handle(200);
    }

    @Override
    public void delete(String uri, Handler<Integer> doneHandler) {

    }
}
