package org.swisspush.gateleen.core.logging;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.swisspush.gateleen.core.util.Address;

import java.util.Map;

/**
 * Created by webermarca on 21.03.2017.
 */
public class RequestLogger {

    private RequestLogger(){}

    public static void logRequest(Vertx vertx, final HttpServerRequest request, final int status, Buffer data, final MultiMap responseHeaders) {

        JsonObject logEntry = new JsonObject();
        logEntry.put("request_uri", request.uri());
        logEntry.put("request_method", request.method().toString());
        logEntry.put("request_headers", headersAsJsonObject(request.headers()));
        logEntry.put("response_headers", headersAsJsonObject(responseHeaders));
        logEntry.put("status", status);
        logEntry.put("payload", data.toJsonObject());
        vertx.eventBus().send(Address.requestLoggingConsumerAddress(), logEntry, reply -> {
            if(reply.failed()){
                System.out.println("fail: " + reply.cause().getMessage());
            }
        });
    }

    private static JsonObject headersAsJsonObject(MultiMap headers){
        JsonObject headersObj = new JsonObject();
        if(headers == null){
            return headersObj;
        }
        for (Map.Entry<String, String> entry : headers) {
            headersObj.put(entry.getKey(), entry.getValue());
        }
        return headersObj;
    }
}
