package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
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
        jedis.hset(circuitInfoKey,"state","half_open");

        jedis.zadd(halfOpenCircuitsKey, 1, "a");
        jedis.zadd(halfOpenCircuitsKey, 2, "someCircuitHash");
        jedis.zadd(halfOpenCircuitsKey, 3, "b");
        jedis.zadd(halfOpenCircuitsKey, 4, "c");

        jedis.zadd(openCircuitsKey, 1, "d");
        jedis.zadd(openCircuitsKey, 2, "e");
        jedis.zadd(openCircuitsKey, 3, "f");
        jedis.zadd(openCircuitsKey, 4, "g");
        jedis.zadd(openCircuitsKey, 5, "h");

        assertThat(jedis.hget(circuitInfoKey, "state"), equalTo("half_open"));
        assertThat(jedis.zcard(halfOpenCircuitsKey), equalTo(4L));
        assertThat(jedis.zcard(openCircuitsKey), equalTo(5L));

        evalScriptReOpenCircuit("someCircuitHash", 1);

        // assertions
        assertThat(jedis.exists(circuitInfoKey), is(true));
        assertThat(jedis.exists(halfOpenCircuitsKey), is(true));
        assertThat(jedis.exists(openCircuitsKey), is(true));

        assertThat(jedis.hget(circuitInfoKey, "state"), equalTo("open"));

        assertThat(jedis.zcard(halfOpenCircuitsKey), equalTo(3L));
        Set<String> halfOpenCircuits = jedis.zrangeByScore(halfOpenCircuitsKey, Long.MIN_VALUE, Long.MAX_VALUE);
        assertThat(halfOpenCircuits.contains("a"), is(true));
        assertThat(halfOpenCircuits.contains("b"), is(true));
        assertThat(halfOpenCircuits.contains("c"), is(true));
        assertThat(halfOpenCircuits.contains("someCircuitHash"), is(false));

        assertThat(jedis.zcard(openCircuitsKey), equalTo(6L));
        Set<String> openCircuits = jedis.zrangeByScore(openCircuitsKey, Long.MIN_VALUE, Long.MAX_VALUE);
        assertThat(openCircuits.contains("d"), is(true));
        assertThat(openCircuits.contains("e"), is(true));
        assertThat(openCircuits.contains("f"), is(true));
        assertThat(openCircuits.contains("g"), is(true));
        assertThat(openCircuits.contains("h"), is(true));
        assertThat(openCircuits.contains("someCircuitHash"), is(true));
    }

    private Object evalScriptReOpenCircuit(String circuitHash, long timestamp){
        String script = readScript(QueueCircuitBreakerLuaScripts.REOPEN_CIRCUIT.getFilename());
        List<String> keys = Arrays.asList(
                circuitInfoKey,
                halfOpenCircuitsKey,
                openCircuitsKey
        );

        List<String> arguments = Arrays.asList(
                circuitHash,
                String.valueOf(timestamp)
        );

        return jedis.eval(script, keys, arguments);
    }
}
