package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.RedisClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

/**
 * Tests for the {@link QueuingHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueuingHandlerTest {
    private Vertx vertx;
    private RedisClient redisClient;
    private MonitoringHandler monitoringHandler;

    @Before
    public void setUp(){
        vertx = Mockito.mock(Vertx.class);
        redisClient = Mockito.mock(RedisClient.class);
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
    }

    @Test
    public void testIsQueued(TestContext context){
        context.assertTrue(QueuingHandler.isQueued(new Request(HttpMethod.PUT, new CaseInsensitiveHeaders()
                .add(QueuingHandler.QUEUE_HEADER, "queue_1"))));
        context.assertFalse(QueuingHandler.isQueued(new Request(HttpMethod.GET, new CaseInsensitiveHeaders()
                .add(QueuingHandler.QUEUE_HEADER, "queue_1"))));
        context.assertFalse(QueuingHandler.isQueued(new Request(HttpMethod.PUT, new CaseInsensitiveHeaders()
                .add(QueuingHandler.QUEUE_HEADER, ""))));
        context.assertFalse(QueuingHandler.isQueued(new Request(HttpMethod.PUT, new CaseInsensitiveHeaders()
                .add("some_other_header", "some_value"))));
        context.assertFalse(QueuingHandler.isQueued(new Request(HttpMethod.PUT, new CaseInsensitiveHeaders()
                .add(QueuingHandler.QUEUE_HEADER, "queue_1")
                .add(QueuingHandler.QUEUE_PROCESSING_HEADER, "true"))));
        context.assertFalse(QueuingHandler.isQueued(new Request(HttpMethod.GET, new CaseInsensitiveHeaders()
                .add(QueuingHandler.QUEUE_HEADER, "queue_1")
                .add(QueuingHandler.QUEUE_PROCESSING_HEADER, "true"))));
        context.assertTrue(QueuingHandler.isQueued(new Request(HttpMethod.PUT, new CaseInsensitiveHeaders()
                .add(QueuingHandler.QUEUE_HEADER, "queue_1")
                .add(QueuingHandler.QUEUE_PROCESSING_HEADER, "false"))));
        context.assertTrue(QueuingHandler.isQueued(new Request(HttpMethod.PUT, new CaseInsensitiveHeaders()
                .add(QueuingHandler.QUEUE_HEADER, "queue_1")
                .add(QueuingHandler.QUEUE_PROCESSING_HEADER, "value not matchable to boolean true"))));
    }

    @Test
    public void testHandleRequest(TestContext context){
        // prepare
        Request r1 = new Request(HttpMethod.PUT, new CaseInsensitiveHeaders().add(QueuingHandler.QUEUE_HEADER, "queue_1"));
        QueuingHandler queuingHandler = new QueuingHandler(vertx, redisClient, r1, monitoringHandler);

        // evaluate
        context.assertNotNull(r1.getHeader(QueuingHandler.QUEUE_HEADER), QueuingHandler.QUEUE_HEADER + " header should be present");
        context.assertNull(r1.getHeader(QueuingHandler.QUEUE_PROCESSING_HEADER), QueuingHandler.QUEUE_PROCESSING_HEADER + " header should not exist yet");
        queuingHandler.handle(Buffer.buffer(""));
        context.assertNotNull(r1.getHeader(QueuingHandler.QUEUE_HEADER), QueuingHandler.QUEUE_HEADER + " header should not have been removed");
        context.assertNotNull(r1.getHeader(QueuingHandler.QUEUE_PROCESSING_HEADER), QueuingHandler.QUEUE_PROCESSING_HEADER + " header should have been added");
    }


    class Request extends DummyHttpServerRequest {

        private MultiMap headers;
        private HttpMethod method;

        public Request(HttpMethod method, MultiMap headers) {
            this.method = method;
            this.headers = headers;
        }

        @Override public HttpMethod method() {return method; }

        @Override
        public MultiMap headers() { return headers; }

        @Override
        public String getHeader(String headerName) { return headers.get(headerName); }

        @Override
        public String uri() {
            return "/some/uri";
        }
    }
}
