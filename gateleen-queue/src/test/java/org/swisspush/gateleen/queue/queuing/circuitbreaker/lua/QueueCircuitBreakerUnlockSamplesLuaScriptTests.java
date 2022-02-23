package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.testhelper.AbstractLuaScriptTest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for the {@link QueueCircuitBreakerLuaScripts#UNLOCK_SAMPLES} lua script.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerUnlockSamplesLuaScriptTests extends AbstractLuaScriptTest {

    private final String halfOpenCircuitsKey = "half_open_circuits";
    private final String circuitQueuesKeyPrefix = "q:";
    private final String circuitQueuesKeySuffix = ":queues";

    @Ignore
    @Test
    public void testUnlockSamples(){

        String c1 = "circuit_1";
        String c2 = "circuit_2";
        String c3 = "circuit_3";

        assertThat(jedis.exists(halfOpenCircuitsKey), is(false));

        assertThat(jedis.exists(circuitQueuesKey(c1)), is(false));
        assertThat(jedis.exists(circuitQueuesKey(c2)), is(false));
        assertThat(jedis.exists(circuitQueuesKey(c3)), is(false));

        // prepare some test data
        jedis.sadd(halfOpenCircuitsKey, c1);
        jedis.sadd(halfOpenCircuitsKey, c2);
        jedis.sadd(halfOpenCircuitsKey, c3);

        jedis.zadd(circuitQueuesKey(c1), 1, "c1_1");
        jedis.zadd(circuitQueuesKey(c1), 2, "c1_2");
        jedis.zadd(circuitQueuesKey(c1), 3, "c1_3");

        jedis.zadd(circuitQueuesKey(c2), 1, "c2_1");
        jedis.zadd(circuitQueuesKey(c2), 2, "c2_2");

        jedis.zadd(circuitQueuesKey(c3), 1, "c3_1");
        jedis.zadd(circuitQueuesKey(c3), 2, "c3_2");
        jedis.zadd(circuitQueuesKey(c3), 3, "c3_3");
        jedis.zadd(circuitQueuesKey(c3), 4, "c3_4");

        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(3L));
        assertThat(jedis.zcard(circuitQueuesKey(c1)), equalTo(3L));
        assertThat(jedis.zcard(circuitQueuesKey(c2)), equalTo(2L));
        assertThat(jedis.zcard(circuitQueuesKey(c3)), equalTo(4L));

        // first lua script execution
        Object result = evalScriptUnlockSamples();
        List<String> queues = (List<String>) result;

        assertThat(queues.contains("c1_1"), is(true));
        assertThat(queues.contains("c2_1"), is(true));
        assertThat(queues.contains("c3_1"), is(true));

        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(3L));
        assertThat(jedis.zcard(circuitQueuesKey(c1)), equalTo(3L));
        assertThat(jedis.zcard(circuitQueuesKey(c2)), equalTo(2L));
        assertThat(jedis.zcard(circuitQueuesKey(c3)), equalTo(4L));

        assertNextQueueWillBe(c1, "c1_2");
        assertNextQueueWillBe(c2, "c2_2");
        assertNextQueueWillBe(c3, "c3_2");

        // second lua script execution
        Object result2 = evalScriptUnlockSamples();
        List<String> queues2 = (List<String>) result2;

        assertThat(queues2.contains("c1_2"), is(true));
        assertThat(queues2.contains("c2_2"), is(true));
        assertThat(queues2.contains("c3_2"), is(true));

        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(3L));
        assertThat(jedis.zcard(circuitQueuesKey(c1)), equalTo(3L));
        assertThat(jedis.zcard(circuitQueuesKey(c2)), equalTo(2L));
        assertThat(jedis.zcard(circuitQueuesKey(c3)), equalTo(4L));

        assertNextQueueWillBe(c1, "c1_3");
        assertNextQueueWillBe(c2, "c2_1"); // first queue again
        assertNextQueueWillBe(c3, "c3_3");

        // third lua script execution
        Object result3 = evalScriptUnlockSamples();
        List<String> queues3 = (List<String>) result3;

        assertThat(queues3.contains("c1_3"), is(true));
        assertThat(queues3.contains("c2_1"), is(true));
        assertThat(queues3.contains("c3_3"), is(true));

        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(3L));
        assertThat(jedis.zcard(circuitQueuesKey(c1)), equalTo(3L));
        assertThat(jedis.zcard(circuitQueuesKey(c2)), equalTo(2L));
        assertThat(jedis.zcard(circuitQueuesKey(c3)), equalTo(4L));

        assertNextQueueWillBe(c1, "c1_1"); // first queue again
        assertNextQueueWillBe(c2, "c2_2");
        assertNextQueueWillBe(c3, "c3_4");

    }

    private void assertNextQueueWillBe(String queueHash, String nextQueue){
        assertThat(jedis.zrange(circuitQueuesKey(queueHash),0,0).size(), equalTo(1));
        assertThat(jedis.zrange(circuitQueuesKey(queueHash),0,0).iterator().next(), equalTo(nextQueue));
    }

    private Object evalScriptUnlockSamples(){
        String script = readScript(QueueCircuitBreakerLuaScripts.UNLOCK_SAMPLES.getFilename());

        List<String> keys = Collections.singletonList(halfOpenCircuitsKey);

        List<String> arguments = Arrays.asList(
                circuitQueuesKeyPrefix,
                circuitQueuesKeySuffix,
                String.valueOf(System.currentTimeMillis())
        );

        return jedis.eval(script, keys, arguments);
    }

    private String circuitQueuesKey(String circuitHash){
        return circuitQueuesKeyPrefix + circuitHash + circuitQueuesKeySuffix;
    }
}
