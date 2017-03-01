package org.swisspush.gateleen.hook.reducedpropagation;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalMatchers;
import org.mockito.Mockito;

import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.timeout;

/**
 * Tests for the {@link ReducedPropagationManager} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ReducedPropagationManagerTest {

    @org.junit.Rule
    public Timeout rule = Timeout.seconds(5);

    private Vertx vertx;
    private ReducedPropagationStorage reducedPropagationStorage;
    private ReducedPropagationManager manager;

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        reducedPropagationStorage = Mockito.mock(ReducedPropagationStorage.class);
        manager = new ReducedPropagationManager(vertx, reducedPropagationStorage, "the_redisques_address");
    }

    @Test
    public void testAddQueueTimerWithStorageError(TestContext context){
        Mockito.when(reducedPropagationStorage.addQueue(eq("queue_boom"), anyLong()))
                .thenReturn(Future.failedFuture("some storage error"));

        long propagationInterval = 500;
        long expectedExpireTS = System.currentTimeMillis() + propagationInterval;
        manager.addQueueTimer("queue_boom", propagationInterval).setHandler(event -> {
            context.assertTrue(event.failed());
            context.assertNotNull(event.cause());
            context.assertTrue(event.cause().getMessage().contains("some storage error"));
            Mockito.verify(reducedPropagationStorage, timeout(1000).times(1)).addQueue(
                    eq("queue_boom"), AdditionalMatchers.geq(expectedExpireTS));
        });
    }

    @Test
    public void testAddQueueTimerWithNewTimer(TestContext context){
        String queueSuccess = "queue_1";
        Mockito.when(reducedPropagationStorage.addQueue(eq(queueSuccess), anyLong()))
                .thenReturn(Future.succeededFuture(Boolean.TRUE));

        long propagationInterval = 500;
        long expectedExpireTS = System.currentTimeMillis() + propagationInterval;
        manager.addQueueTimer(queueSuccess, propagationInterval).setHandler(event -> {
            context.assertTrue(event.succeeded());
            Mockito.verify(reducedPropagationStorage, timeout(1000).times(1)).addQueue(
                    eq(queueSuccess), AdditionalMatchers.geq(expectedExpireTS));
        });
    }

    @Test
    public void testAddQueueTimerWithExistingTimer(TestContext context){
        String queueSuccess = "queue_1";
        Mockito.when(reducedPropagationStorage.addQueue(eq(queueSuccess), anyLong()))
                .thenReturn(Future.succeededFuture(Boolean.TRUE), Future.succeededFuture(Boolean.FALSE));

        long propagationInterval = 500;
        long expectedExpireTS = System.currentTimeMillis() + propagationInterval;
        manager.addQueueTimer(queueSuccess, propagationInterval).setHandler(event -> {
            context.assertTrue(event.succeeded());
            manager.addQueueTimer(queueSuccess, propagationInterval).setHandler(event1 -> {
                context.assertTrue(event1.succeeded());
                Mockito.verify(reducedPropagationStorage, timeout(1000).times(2)).addQueue(
                        eq(queueSuccess), AdditionalMatchers.geq(expectedExpireTS));
            });
        });
    }
}
