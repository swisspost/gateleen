package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import org.junit.Test;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitState;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.RedisQueueCircuitBreakerStorage.FIELD_FAILRATIO;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.RedisQueueCircuitBreakerStorage.FIELD_STATE;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerCloseCircuitLuaScriptTests extends AbstractLuaScriptTest {

    private final String circuitInfoKey = "q:infos";
    private final String circuitSuccessKey = "q:success";
    private final String circuitFailureKey = "q:failure";
    private final String circuitQueuesKey = "q:queues";
    private final String halfOpenCircuitsKey = "half_open_circuits";
    private final String openCircuitsKey = "open_circuits";
    private final String queuesToUnlockKey = "queues_to_unlock";

    @Test
    public void testCloseCircuit(){
        assertThat(jedis.exists(circuitInfoKey), is(false));
        assertThat(jedis.exists(circuitSuccessKey), is(false));
        assertThat(jedis.exists(circuitFailureKey), is(false));
        assertThat(jedis.exists(circuitQueuesKey), is(false));
        assertThat(jedis.exists(halfOpenCircuitsKey), is(false));
        assertThat(jedis.exists(queuesToUnlockKey), is(false));

        // prepare some test data
        jedis.hset(circuitInfoKey, FIELD_STATE, QueueCircuitState.HALF_OPEN.name().toLowerCase());
        jedis.hset(circuitInfoKey, FIELD_FAILRATIO, "50");
        jedis.zadd(circuitSuccessKey, 1, "req-1");
        jedis.zadd(circuitSuccessKey, 2, "req-2");
        jedis.zadd(circuitSuccessKey, 3, "req-3");
        jedis.zadd(circuitFailureKey, 4, "req-4");
        jedis.zadd(circuitFailureKey, 5, "req-5");
        jedis.zadd(circuitFailureKey, 6, "req-6");

        jedis.zadd(circuitQueuesKey, 1, "queue_1");
        jedis.zadd(circuitQueuesKey, 2, "queue_2");
        jedis.zadd(circuitQueuesKey, 3, "queue_3");

        jedis.sadd(halfOpenCircuitsKey, "a");
        jedis.sadd(halfOpenCircuitsKey, "someCircuitHash");
        jedis.sadd(halfOpenCircuitsKey, "b");
        jedis.sadd(halfOpenCircuitsKey, "c");

        assertThat(jedis.zcard(circuitSuccessKey), equalTo(3L));
        assertThat(jedis.zcard(circuitFailureKey), equalTo(3L));
        assertThat(jedis.zcard(circuitQueuesKey), equalTo(3L));
        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(4L));
        assertThat(jedis.llen(queuesToUnlockKey), equalTo(0L));

        evalScriptCloseCircuit("someCircuitHash");

        // assertions
        assertThat(jedis.exists(circuitInfoKey), is(true));
        assertThat(jedis.exists(circuitSuccessKey), is(false));
        assertThat(jedis.exists(circuitFailureKey), is(false));
        assertThat(jedis.exists(circuitQueuesKey), is(false));
        assertThat(jedis.exists(halfOpenCircuitsKey), is(true));
        assertThat(jedis.exists(queuesToUnlockKey), is(true));

        assertThat(jedis.llen(queuesToUnlockKey), equalTo(3L));
        assertThat(jedis.lpop(queuesToUnlockKey), equalTo("queue_1"));
        assertThat(jedis.lpop(queuesToUnlockKey), equalTo("queue_2"));
        assertThat(jedis.lpop(queuesToUnlockKey), equalTo("queue_3"));

        assertThat(jedis.hget(circuitInfoKey, FIELD_STATE).toLowerCase(), equalTo(QueueCircuitState.CLOSED.name().toLowerCase()));
        assertThat(jedis.hget(circuitInfoKey, FIELD_FAILRATIO), equalTo("0"));

        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(3L));
        Set<String> halfOpenCircuits = jedis.smembers(halfOpenCircuitsKey);
        assertThat(halfOpenCircuits.contains("a"), is(true));
        assertThat(halfOpenCircuits.contains("b"), is(true));
        assertThat(halfOpenCircuits.contains("c"), is(true));
        assertThat(halfOpenCircuits.contains("someCircuitHash"), is(false));
    }

    private Object evalScriptCloseCircuit(String circuitHash){
        String script = readScript(QueueCircuitBreakerLuaScripts.CLOSE_CIRCUIT.getFilename());
        List<String> keys = Arrays.asList(
                circuitInfoKey,
                circuitSuccessKey,
                circuitFailureKey,
                circuitQueuesKey,
                halfOpenCircuitsKey,
                openCircuitsKey,
                queuesToUnlockKey
        );

        List<String> arguments = Collections.singletonList(circuitHash);
        return jedis.eval(script, keys, arguments);
    }
}
