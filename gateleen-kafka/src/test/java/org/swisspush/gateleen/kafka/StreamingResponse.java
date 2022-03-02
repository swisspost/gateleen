package org.swisspush.gateleen.kafka;

import io.vertx.core.MultiMap;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;

public class StreamingResponse extends DummyHttpServerResponse {

    private final MultiMap headers;

    StreamingResponse(){
        this.headers = new HeadersMultiMap();
    }

    StreamingResponse(MultiMap headers){
        this.headers = headers;
    }

    @Override public MultiMap headers() { return headers; }
}
