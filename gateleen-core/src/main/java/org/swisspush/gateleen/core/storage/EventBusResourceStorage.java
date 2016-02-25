package org.swisspush.gateleen.core.storage;

import org.swisspush.gateleen.core.http.HttpRequest;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;

/**
 * Created by bovetl on 26.01.2015.
 */
public class EventBusResourceStorage implements ResourceStorage {

    private EventBus eventBus;
    private String address;

    public EventBusResourceStorage(EventBus eventBus, String address) {
        this.eventBus = eventBus;
        this.address = address;
    }

    @Override
    public void get(String uri, final Handler<Buffer> bodyHandler) {
        Buffer header = Buffer.buffer(new HttpRequest(HttpMethod.GET, uri, null, null).toJsonObject().encode());
        Buffer request = Buffer.buffer(4+header.length());
        request.setInt(0, header.length()).appendBuffer(header);
        eventBus.send(address, request, new Handler<AsyncResult<Message<Buffer>>>() {
            @Override
            public void handle(AsyncResult<Message<Buffer>> message) {
                Buffer buffer = message.result().body();
                int headerLength = buffer.getInt(0);
                JsonObject header = new JsonObject(buffer.getString(4, headerLength+4));
                if(header.getInteger("statusCode") == 200) {
                    bodyHandler.handle(buffer.getBuffer(4+headerLength, buffer.length()));
                } else {
                    bodyHandler.handle(null);
                }
            }
        });
    }

    @Override
    public void put(String uri, MultiMap headers, Buffer buffer, final Handler<Integer> doneHandler) {
        Buffer header = Buffer.buffer(new HttpRequest(HttpMethod.PUT, uri, headers, null).toJsonObject().encode());
        Buffer request = Buffer.buffer(4+header.length());
        request.setInt(0, header.length()).appendBuffer(header).appendBuffer(buffer);
        eventBus.send(address, request, new Handler<AsyncResult<Message<Buffer>>>() {
            @Override
            public void handle(AsyncResult<Message<Buffer>> message) {
                Buffer buffer = message.result().body();
                int headerLength = buffer.getInt(0);
                JsonObject header = new JsonObject(buffer.getString(4, headerLength+4));
                doneHandler.handle(header.getInteger("statusCode"));
            }
        });
    }

    @Override
    public void put(String uri, Buffer buffer, Handler<Integer> doneHandler) {
        put(uri, null, buffer, doneHandler);
    }

    @Override
    public void delete(String uri, final Handler<Integer> doneHandler) {
        Buffer header = Buffer.buffer(new HttpRequest(HttpMethod.DELETE, uri, null, null).toJsonObject().encode());
        Buffer request = Buffer.buffer(4+header.length());
        request.setInt(0, header.length()).appendBuffer(header);
        eventBus.send(address, request, new Handler<AsyncResult<Message<Buffer>>>() {
            @Override
            public void handle(AsyncResult<Message<Buffer>> message) {
                    Buffer buffer = message.result().body();
                    int headerLength = buffer.getInt(0);
                    JsonObject header = new JsonObject(buffer.getString(4, headerLength+4));
                    doneHandler.handle(header.getInteger("statusCode"));
                }
            });
    }

}
