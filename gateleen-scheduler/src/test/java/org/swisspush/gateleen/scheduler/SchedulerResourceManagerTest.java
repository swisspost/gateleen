package org.swisspush.gateleen.scheduler;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.client.RedisAPI;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.monitoring.MonitoringHandler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for the {@link SchedulerResourceManager} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class SchedulerResourceManagerTest {

    private Vertx vertx;
    private MonitoringHandler monitoringHandler;
    private RedisProvider redisProvider;
    private ResourceStorage storage;
    private SchedulerResourceManager schedulerResourceManager;

    private final String schedulersUri = "/playground/server/admin/v1/schedulers";

    private final String VALID_SCHEDULER_RESOURCE = ResourcesUtils.loadResource("testresource_valid_scheduler_resource", true);
    private final String INVALID_JSON = ResourcesUtils.loadResource("testresource_invalid_json", true);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();

        RedisAPI redisAPI = Mockito.mock(RedisAPI.class);
        redisProvider = Mockito.mock(RedisProvider.class);
        when(redisProvider.redis()).thenReturn(Future.succeededFuture(redisAPI));

        monitoringHandler = Mockito.mock(MonitoringHandler.class);

        storage = Mockito.spy(new MockResourceStorage(Collections.emptyMap()));
    }

    @Test
    public void testReadSchedulersResource(TestContext context){
        schedulerResourceManager = new SchedulerResourceManager(vertx, redisProvider, storage, monitoringHandler,
                schedulersUri);

        // on initialization, the schedulers must be read from storage
        verify(storage, timeout(100).times(1)).get(eq(schedulersUri), any());

        // reset the mock to start new count after eventbus message
        reset(storage);

        vertx.eventBus().publish("gateleen.schedulers-updated", true);

        // after eventbus message, the schedulers must be read from storage again
        verify(storage, timeout(100).times(1)).get(eq(schedulersUri), any());
    }

    @Test
    public void testHandleValidSchedulerResource(TestContext context){
        schedulerResourceManager = new SchedulerResourceManager(vertx, redisProvider, storage, monitoringHandler,
                schedulersUri);

        HttpServerResponse response = spy(new SchedulerResourceResponse());
        SchedulerResourceRequest request = new SchedulerResourceRequest(HttpMethod.PUT, schedulersUri, VALID_SCHEDULER_RESOURCE, response);

        schedulerResourceManager.handleSchedulerResource(request);

        verify(response, timeout(100).times(1)).end();
    }

    @Test
    public void testHandleInvalidSchedulerResource(TestContext context){
        schedulerResourceManager = new SchedulerResourceManager(vertx, redisProvider, storage, monitoringHandler,
                schedulersUri);

        HttpServerResponse response = spy(new SchedulerResourceResponse());
        SchedulerResourceRequest request = new SchedulerResourceRequest(HttpMethod.PUT, schedulersUri, INVALID_JSON, response);

        schedulerResourceManager.handleSchedulerResource(request);

        verify(response, timeout(100).times(1)).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
    }

    @Test
    public void testDaylightSavingsTimeChangeObserver() {
        Map<String, Object> props = new HashMap<>();
        props.put("dst.observe", true);
        schedulerResourceManager = new SchedulerResourceManager(vertx, redisProvider, storage, monitoringHandler,
                schedulersUri, props);

        boolean dstStateSummerBefore = schedulerResourceManager.isDaylightSavingTimeState(getDate("2025-10-25T02:00:01Z"));
        boolean dstStateWinter1 = schedulerResourceManager.isDaylightSavingTimeState(getDate("2025-10-26T02:00:01Z"));
        boolean dstStateWinter2 = schedulerResourceManager.isDaylightSavingTimeState(getDate("2026-03-28T02:00:01Z"));
        boolean dstStateSummerAfter = schedulerResourceManager.isDaylightSavingTimeState(getDate("2026-03-29T02:00:01Z"));

        // we expect a change in the dst state from summer to winter and vv
        assertNotEquals(dstStateSummerBefore, dstStateWinter1);
        assertNotEquals(dstStateSummerAfter, dstStateWinter1);
        assertEquals(dstStateWinter1, dstStateWinter2);
    }

    private static Date getDate(String time) {
        Instant instant = Instant.now(Clock.fixed(Instant.parse(time), ZoneId.of( "Europe/Zurich")));
        return new Date(instant.toEpochMilli());
    }

    static class SchedulerResourceRequest extends DummyHttpServerRequest {
        private final String uri;
        private final HttpMethod method;
        private final String body;
        private final HttpServerResponse response;

        SchedulerResourceRequest(HttpMethod method, String uri, String body, HttpServerResponse response) {
            this.method = method;
            this.uri = uri;
            this.body = body;
            this.response = response;
        }

        @Override public HttpMethod method() {
            return method;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
            bodyHandler.handle(Buffer.buffer(body));
            return this;
        }

        @Override
        public MultiMap headers() {
            return MultiMap.caseInsensitiveMultiMap();
        }

        @Override public HttpServerResponse response() { return response; }


    }

    static class SchedulerResourceResponse extends DummyHttpServerResponse {

        private final MultiMap headers;

        SchedulerResourceResponse(){
            this.headers = MultiMap.caseInsensitiveMultiMap();
        }

        @Override public MultiMap headers() { return headers; }
    }
}
