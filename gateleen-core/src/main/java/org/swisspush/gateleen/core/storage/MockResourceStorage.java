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

    private Integer putRequestFailValue = null;
    private Integer deleteRequestFailValue = null;

    public MockResourceStorage(){}

    public MockResourceStorage(Map<String, String> initalMockData){
        localStorageValues.putAll(initalMockData);
    }

    public void putMockData(String key, String value){
        localStorageValues.put(key, value);
    }

    public void failPutWith(Integer value){ this.putRequestFailValue = value; }

    public void failDeleteWith(Integer value){ this.deleteRequestFailValue = value; }

    /**
     * Synchronous access to the mocked data
     */
    public Map<String, String> getMockData(){
        return localStorageValues;
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
        if(putRequestFailValue != null){
            doneHandler.handle(putRequestFailValue);
        } else {
            localStorageValues.put(uri, buffer.toString());
            doneHandler.handle(StatusCode.OK.getStatusCode());
        }
    }

    @Override
    public void put(String uri, Buffer buffer, Handler<Integer> doneHandler) {
        if(putRequestFailValue != null){
            doneHandler.handle(putRequestFailValue);
        } else {
            localStorageValues.put(uri, buffer.toString());
            doneHandler.handle(StatusCode.OK.getStatusCode());
        }
    }

    @Override
    public void delete(String uri, Handler<Integer> doneHandler) {
        if(deleteRequestFailValue != null){
            doneHandler.handle(deleteRequestFailValue);
        }else {
            localStorageValues.remove(uri);
            doneHandler.handle(StatusCode.OK.getStatusCode());
        }
    }
}
