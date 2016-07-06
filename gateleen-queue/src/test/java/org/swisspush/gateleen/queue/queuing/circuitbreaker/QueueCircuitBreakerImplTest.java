package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.routing.RuleProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.mockito.Matchers.*;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueResponseType.*;

/**
 * Tests for the {@link QueueCircuitBreakerImpl} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerImplTest {

    private Vertx vertx;
    private ResourceStorage storage;
    private RuleProvider ruleProvider;
    private QueueCircuitBreakerStorage queueCircuitBreakerStorage;
    private Map<String, Object> props = new HashMap<>();
    private QueueCircuitBreakerImpl queueCircuitBreaker;
    private QueueCircuitBreakerRulePatternToEndpointMapping ruleToEndpointMapping;

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        storage = Mockito.mock(ResourceStorage.class);
        queueCircuitBreakerStorage = Mockito.mock(QueueCircuitBreakerStorage.class);
        ruleProvider = new RuleProvider(vertx, "/path/to/routing/rules", storage, props);

        ruleToEndpointMapping = Mockito.mock(QueueCircuitBreakerRulePatternToEndpointMapping.class);

        queueCircuitBreaker = new QueueCircuitBreakerImpl(vertx, queueCircuitBreakerStorage, ruleProvider, ruleToEndpointMapping);
    }

    @Test
    public void testHandleQueuedRequest(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToEndpointMapping.getEndpointFromRequestUri(anyString()))
                .thenReturn(new PatternAndEndpointHash(Pattern.compile("/someEndpoint"), "someEndpointHash"));

        Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndEndpointHash.class)))
                .thenReturn(Future.succeededFuture(QueueCircuitState.CLOSED));

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertEquals(QueueCircuitState.CLOSED, event.result());

            Mockito.when(queueCircuitBreakerStorage.getQueueCircuitState(any(PatternAndEndpointHash.class)))
                    .thenReturn(Future.succeededFuture(QueueCircuitState.OPEN));

            queueCircuitBreaker.handleQueuedRequest("someQueue", req).setHandler(event1 -> {
                context.assertTrue(event1.succeeded());
                context.assertEquals(QueueCircuitState.OPEN, event1.result());
                async.complete();
            });
        });
    }

    @Test
    public void testHandleQueuedRequestNoEndpointMapping(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToEndpointMapping.getEndpointFromRequestUri(anyString())).thenReturn(null);

        queueCircuitBreaker.handleQueuedRequest("someQueue", req).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertNotNull(event.cause());
            context.assertTrue(event.cause().getMessage().contains("no rule to endpoint mapping found for queue"));
            async.complete();
        });
    }

    @Test
    public void testUpdateStatistics(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToEndpointMapping.getEndpointFromRequestUri(anyString()))
                .thenReturn(new PatternAndEndpointHash(Pattern.compile("/someEndpoint"), "someEndpointHash"));

        Mockito.when(queueCircuitBreakerStorage.updateStatistics(any(PatternAndEndpointHash.class),
                anyString(), anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any(QueueResponseType.class)))
                .thenReturn(Future.succeededFuture(UpdateStatisticsResult.OK));

        queueCircuitBreaker.updateStatistics("someQueue", req, SUCCESS).setHandler(event -> {
            context.assertTrue(event.succeeded());
            async.complete();
        });
    }

    @Test
    public void testUpdateStatisticsNoEndpointMapping(TestContext context){
        Async async = context.async();
        HttpRequest req = new HttpRequest(HttpMethod.PUT, "/playground/circuitBreaker/test", MultiMap.caseInsensitiveMultiMap(), null);

        Mockito.when(ruleToEndpointMapping.getEndpointFromRequestUri(anyString())).thenReturn(null);

        queueCircuitBreaker.updateStatistics("someQueueName", req, SUCCESS).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertNotNull(event.cause());
            context.assertTrue(event.cause().getMessage().contains("no rule to endpoint mapping found for queue"));
            async.complete();
        });
    }

}
