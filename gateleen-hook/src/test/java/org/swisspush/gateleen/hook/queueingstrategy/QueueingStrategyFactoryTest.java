package org.swisspush.gateleen.hook.queueingstrategy;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for the {@link QueueingStrategyFactory} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueingStrategyFactoryTest {

    @Test
    public void testDefaultQueueingStrategy(){
        assertThat(QueueingStrategyFactory.buildQueueStrategy(null), instanceOf(DefaultQueueingStrategy.class));
        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(null)), instanceOf(DefaultQueueingStrategy.class));

        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(null).put("queueingStrategy", "not_a_jsonobject")),
                instanceOf(DefaultQueueingStrategy.class));
        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(null).put("queueingStrategy", new JsonArray())),
                instanceOf(DefaultQueueingStrategy.class));
        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(null).put("queueingStrategy", 123)),
                instanceOf(DefaultQueueingStrategy.class));

        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("type", "unknownType"))),
                instanceOf(DefaultQueueingStrategy.class));
    }

    @Test
    public void testDiscardPayloadQueueingStrategy(){
        // valid DiscardPayloadQueueingStrategy config
        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("type", "discardPayload"))),
                instanceOf(DiscardPayloadQueueingStrategy.class));
        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("type", "discardpayload"))),
                instanceOf(DiscardPayloadQueueingStrategy.class));
        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("type", "DISCARDPAYLOAD"))),
                instanceOf(DiscardPayloadQueueingStrategy.class));

        // invalid DiscardPayloadQueueingStrategy config
        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("typeXX", "discardPayload"))),
                instanceOf(DefaultQueueingStrategy.class));
    }

    @Test
    public void testReducedPropagationQueueingStrategy(){
        // valid ReducedPropagationQueueingStrategy config
        QueueingStrategy queueingStrategy = QueueingStrategyFactory.buildQueueStrategy(
                buildHookConfig(new JsonObject().put("type", "reducedPropagation").put("intervalMs", 22)));
        assertThat(queueingStrategy, instanceOf(ReducedPropagationQueueingStrategy.class));
        assertEquals(22, ((ReducedPropagationQueueingStrategy)queueingStrategy).getPropagationIntervalMs());

        queueingStrategy = QueueingStrategyFactory.buildQueueStrategy(
                buildHookConfig(new JsonObject().put("type", "reducedpropagation").put("intervalMs", 888)));
        assertThat(queueingStrategy, instanceOf(ReducedPropagationQueueingStrategy.class));
        assertEquals(888, ((ReducedPropagationQueueingStrategy)queueingStrategy).getPropagationIntervalMs());

        queueingStrategy = QueueingStrategyFactory.buildQueueStrategy(
                buildHookConfig(new JsonObject().put("type", "REDUCEDPROPAGATION").put("intervalMs", 999)));
        assertThat(queueingStrategy, instanceOf(ReducedPropagationQueueingStrategy.class));
        assertEquals(999, ((ReducedPropagationQueueingStrategy)queueingStrategy).getPropagationIntervalMs());

        queueingStrategy = QueueingStrategyFactory.buildQueueStrategy(
                buildHookConfig(new JsonObject().put("type", "REDUCEDPROPAGATION").put("intervalMs", 999999999999999999L)));
        assertThat(queueingStrategy, instanceOf(ReducedPropagationQueueingStrategy.class));
        assertEquals(999999999999999999L, ((ReducedPropagationQueueingStrategy)queueingStrategy).getPropagationIntervalMs());

        // invalid ReducedPropagationQueueingStrategy config
        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("type", "reducedPropagation"))),
                instanceOf(DefaultQueueingStrategy.class));

        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("typeXX", "reducedPropagation").put("intervalMs", 123))),
                instanceOf(DefaultQueueingStrategy.class));

        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("type", "reducedPropagation").put("intervalMs", "234"))),
                instanceOf(DefaultQueueingStrategy.class));

        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("type", "reducedPropagation").put("intervalXX", 234))),
                instanceOf(DefaultQueueingStrategy.class));

        assertThat(QueueingStrategyFactory.buildQueueStrategy(buildHookConfig(new JsonObject().put("type", "reducedPropagation").put("intervalMs", new JsonObject()))),
                instanceOf(DefaultQueueingStrategy.class));
    }

    private JsonObject buildHookConfig(JsonObject queueingStrategy){
        JsonObject hookConfig = new JsonObject();
        hookConfig.put("destination", "/playground/server/push/v1/devices/x99");
        hookConfig.put("methods", new JsonArray(Collections.singletonList("PUT")));
        hookConfig.put("expireAfter", 300);
        hookConfig.put("fullUrl", true);
        JsonObject staticHeaders = new JsonObject();
        staticHeaders.put("x-sync", true);
        hookConfig.put("staticHeaders", staticHeaders);
        if(queueingStrategy != null){
            hookConfig.put("queueingStrategy", queueingStrategy);
        }
        return hookConfig;
    }
}
