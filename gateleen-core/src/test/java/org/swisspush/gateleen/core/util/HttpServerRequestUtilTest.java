package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;

/**
 * <p>
 * Tests for the {@link HttpServerRequestUtil} class
 * </p>
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class HttpServerRequestUtilTest {

    @Test
    public void testIsRemoteAddressLoopbackAddress(TestContext context){
        context.assertTrue(HttpServerRequestUtil.isRemoteAddressLoopbackAddress(new RemoteAddressRequest("127.0.0.1")));
        context.assertTrue(HttpServerRequestUtil.isRemoteAddressLoopbackAddress(new RemoteAddressRequest("localhost")));
        context.assertFalse(HttpServerRequestUtil.isRemoteAddressLoopbackAddress(new RemoteAddressRequest("")));
    }

    @Test
    public void testIncreaseRequestHops(TestContext context){
        IncreaseHopsRequest request = new IncreaseHopsRequest(new CaseInsensitiveHeaders());

        context.assertFalse(HttpRequestHeader.containsHeader(request.headers(), HttpRequestHeader.X_HOPS),
                "x-hops header should not be present yet");

        HttpServerRequestUtil.increaseRequestHops(request);

        context.assertTrue(HttpRequestHeader.containsHeader(request.headers(), HttpRequestHeader.X_HOPS),
                "x-hops header should be present");
        context.assertEquals(1, HttpRequestHeader.getInteger(request.headers(), HttpRequestHeader.X_HOPS));

        HttpServerRequestUtil.increaseRequestHops(request);
        context.assertEquals(2, HttpRequestHeader.getInteger(request.headers(), HttpRequestHeader.X_HOPS));

        for (int i = 0; i < 500; i++) {
            HttpServerRequestUtil.increaseRequestHops(request);
        }

        context.assertEquals(502, HttpRequestHeader.getInteger(request.headers(), HttpRequestHeader.X_HOPS));
    }

    class IncreaseHopsRequest extends DummyHttpServerRequest {

        private MultiMap headers;

        public IncreaseHopsRequest(MultiMap headers) {
            this.headers = headers;
        }

        @Override public MultiMap headers() { return headers; }
    }

    class RemoteAddressRequest extends DummyHttpServerRequest {

        private String host;

        public RemoteAddressRequest(String host) {
            this.host = host;
        }

        @Override
        public SocketAddress remoteAddress() {
            return new SocketAddress() {
                @Override
                public String host() { return host; }

                @Override
                public int port() { return 0; }
            };
        }
    }
}
