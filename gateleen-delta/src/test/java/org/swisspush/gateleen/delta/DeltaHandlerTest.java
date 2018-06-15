package org.swisspush.gateleen.delta;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.redis.RedisClient;
import org.junit.Before;
import org.junit.Test;
import org.swisspush.gateleen.routing.Router;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class DeltaHandlerTest {

    private RedisClient redisClient;
    private Router router = mock(Router.class);
    private HttpServerRequest request;
    private CaseInsensitiveHeaders requestHeaders = new CaseInsensitiveHeaders();

    @Before
    public void before() {
        redisClient = mock(RedisClient.class);
        doAnswer(invocation -> {
            Handler<AsyncResult<Long>> handler = (Handler<AsyncResult<Long>>) invocation.getArguments()[1];
            handler.handle(Future.succeededFuture(555L));
            return null;
        }).when(redisClient).incr(eq("delta:sequence"), any());

        requestHeaders = new CaseInsensitiveHeaders();
        requestHeaders.add("x-delta", "auto");

        request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.path()).thenReturn("/a/b/c");
        when(request.headers()).thenReturn(requestHeaders);
    }

    @Test
    public void testDeltaNoExpiry() {
        DeltaHandler deltaHandler = new DeltaHandler(redisClient, null);
        deltaHandler.handle(request, router);

        verify(redisClient, times(1)).set(eq("delta:resources:a:b:c"), eq("555"), any());
        verify(redisClient, never()).setex(any(), anyLong(), any(), any());
    }

    @Test
    public void testDeltaWithExpiry() {
        requestHeaders.add("x-expire-after", "123");

        DeltaHandler deltaHandler = new DeltaHandler(redisClient, null);
        deltaHandler.handle(request, router);

        verify(redisClient, times(1)).setex(eq("delta:resources:a:b:c"), eq(123L), eq("555"), any());
        verify(redisClient, never()).set(any(), any(), any());
    }
}
