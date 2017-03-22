package org.swisspush.gateleen.core.logging;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.StatusCode;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.swisspush.gateleen.core.logging.RequestLogger.*;

/**
 * <p>
 * Tests for the {@link RequestLogger} class
 * </p>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RequestLoggerTest {

    @Test
    public void testLogRequest(TestContext context){
        EventBus eventBus = Mockito.mock(EventBus .class);
        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add("Content-Type", "application/json");
        headers.add("x-rp-unique-id", "123456");
        MockedResponse response = new MockedResponse(new CaseInsensitiveHeaders());
        MockedRequest request = new MockedRequest("/uri/to/a/resource", HttpMethod.PUT, headers, response);
        JsonObject body = new JsonObject().put("key_1", "value_2").put("key_2", 99);

        RequestLogger.logRequest(eventBus, request, StatusCode.OK.getStatusCode(), Buffer.buffer(body.encode()));

        JsonObject expected = new JsonObject();
        expected.put(REQUEST_URI, "/uri/to/a/resource");
        expected.put(REQUEST_METHOD, "PUT");
        JsonObject requestHeaders = new JsonObject().put("Content-Type", "application/json").put("x-rp-unique-id", "123456");
        expected.put(REQUEST_HEADERS, requestHeaders);
        expected.put(RESPONSE_HEADERS, new JsonObject());
        expected.put(REQUEST_STATUS, StatusCode.OK.getStatusCode());
        expected.put(BODY, Buffer.buffer(body.encode()).toString());

        Mockito.verify(eventBus, Mockito.times(1))
                .send(eq(Address.requestLoggingConsumerAddress()), eq(expected), any(Handler.class));
    }

    @Test
    public void testLogRequestResponseHeaders(TestContext context){
        EventBus eventBus = Mockito.mock(EventBus .class);
        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add("Content-Type", "application/json");
        headers.add("x-rp-unique-id", "123456");
        MockedRequest request = new MockedRequest("/uri/to/a/resource", HttpMethod.PUT, headers,
                new MockedResponse(new CaseInsensitiveHeaders()));
        JsonObject body = new JsonObject().put("key_1", "value_2").put("key_2", 99);

        CaseInsensitiveHeaders responseHeaders = new CaseInsensitiveHeaders();
        responseHeaders.add("header_1", "value_1");
        responseHeaders.add("header_2", "value_2");

        RequestLogger.logRequest(eventBus, request, StatusCode.OK.getStatusCode(), Buffer.buffer(body.encode()), responseHeaders);

        JsonObject expected = new JsonObject();
        expected.put(REQUEST_URI, "/uri/to/a/resource");
        expected.put(REQUEST_METHOD, "PUT");
        JsonObject requestHeaders = new JsonObject().put("Content-Type", "application/json").put("x-rp-unique-id", "123456");
        expected.put(REQUEST_HEADERS, requestHeaders);
        JsonObject responseHeadersJsonObject = new JsonObject().put("header_1", "value_1").put("header_2", "value_2");
        expected.put(RESPONSE_HEADERS, responseHeadersJsonObject);
        expected.put(REQUEST_STATUS, StatusCode.OK.getStatusCode());
        expected.put(BODY, Buffer.buffer(body.encode()).toString());

        Mockito.verify(eventBus, Mockito.times(1))
                .send(eq(Address.requestLoggingConsumerAddress()), eq(expected), any(Handler.class));
    }

    class MockedRequest extends DummyHttpServerRequest {

        private String uri;
        private HttpMethod method;
        private MultiMap headers;
        private HttpServerResponse response;

        public MockedRequest(String uri, HttpMethod method, MultiMap headers, HttpServerResponse response) {
            this.uri = uri;
            this.method = method;
            this.headers = headers;
            this.response = response;
        }

        @Override public HttpMethod method() {
            return method;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public MultiMap headers() { return headers; }
        @Override public HttpServerResponse response() { return response; }
    }

    class MockedResponse extends DummyHttpServerResponse {

        private MultiMap headers;

        public MockedResponse(MultiMap headers){
            this.headers = headers;
        }

        @Override public MultiMap headers() { return headers; }
    }
}
