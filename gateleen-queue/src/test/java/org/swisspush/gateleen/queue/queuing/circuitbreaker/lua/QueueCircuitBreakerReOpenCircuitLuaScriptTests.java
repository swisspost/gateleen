package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.testhelper.AbstractLuaScriptTest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage.FIELD_STATE;

/**
 * Tests for the {@link QueueCircuitBreakerLuaScripts#REOPEN_CIRCUIT} lua script.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerReOpenCircuitLuaScriptTests extends AbstractLuaScriptTest {

    private final String circuitInfoKey = "q:infos";
    private final String halfOpenCircuitsKey = "half_open_circuits";
    private final String openCircuitsKey = "open_circuits";

    @Test
    public void testReOpenCircuit(){
        assertThat(jedis.exists(circuitInfoKey), is(false));
        assertThat(jedis.exists(halfOpenCircuitsKey), is(false));
        assertThat(jedis.exists(openCircuitsKey), is(false));

        // prepare some test data
        jedis.hset(circuitInfoKey,FIELD_STATE, QueueCircuitState.HALF_OPEN.name().toLowerCase());

        jedis.sadd(halfOpenCircuitsKey, "a");
        jedis.sadd(halfOpenCircuitsKey, "someCircuitHash");
        jedis.sadd(halfOpenCircuitsKey, "b");
        jedis.sadd(halfOpenCircuitsKey, "c");

        jedis.sadd(openCircuitsKey, "d");
        jedis.sadd(openCircuitsKey, "e");
        jedis.sadd(openCircuitsKey, "f");
        jedis.sadd(openCircuitsKey, "g");
        jedis.sadd(openCircuitsKey, "h");

        assertThat(jedis.hget(circuitInfoKey, FIELD_STATE).toLowerCase(), equalTo(QueueCircuitState.HALF_OPEN.name().toLowerCase()));
        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(4L));
        assertThat(jedis.scard(openCircuitsKey), equalTo(5L));

        evalScriptReOpenCircuit("someCircuitHash");

        // assertions
        assertThat(jedis.exists(circuitInfoKey), is(true));
        assertThat(jedis.exists(halfOpenCircuitsKey), is(true));
        assertThat(jedis.exists(openCircuitsKey), is(true));

        assertThat(jedis.hget(circuitInfoKey, FIELD_STATE).toLowerCase(), equalTo(QueueCircuitState.OPEN.name().toLowerCase()));

        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(3L));
        Set<String> halfOpenCircuits = jedis.smembers(halfOpenCircuitsKey);
        assertThat(halfOpenCircuits.contains("a"), is(true));
        assertThat(halfOpenCircuits.contains("b"), is(true));
        assertThat(halfOpenCircuits.contains("c"), is(true));
        assertThat(halfOpenCircuits.contains("someCircuitHash"), is(false));

        assertThat(jedis.scard(openCircuitsKey), equalTo(6L));
        Set<String> openCircuits = jedis.smembers(openCircuitsKey);
        assertThat(openCircuits.contains("d"), is(true));
        assertThat(openCircuits.contains("e"), is(true));
        assertThat(openCircuits.contains("f"), is(true));
        assertThat(openCircuits.contains("g"), is(true));
        assertThat(openCircuits.contains("h"), is(true));
        assertThat(openCircuits.contains("someCircuitHash"), is(true));
    }

    private Object evalScriptReOpenCircuit(String circuitHash){
        String script = readScript(QueueCircuitBreakerLuaScripts.REOPEN_CIRCUIT.getFilename());
        List<String> keys = Arrays.asList(
                circuitInfoKey,
                halfOpenCircuitsKey,
                openCircuitsKey
        );
        List<String> arguments = Collections.singletonList(circuitHash);
        return jedis.eval(script, keys, arguments);
    }
}
