package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.testhelper.AbstractLuaScriptTest;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState.HALF_OPEN;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState.OPEN;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage.FIELD_STATE;

/**
 * Tests for the {@link QueueCircuitBreakerLuaScripts#HALFOPEN_CIRCUITS} lua script.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerHalfOpenCircuitsLuaScriptTests extends AbstractLuaScriptTest {

    private final String circuitInfoKeyPrefix = "q:";
    private final String circuitInfoKeySuffix = ":infos";
    private final String halfOpenCircuitsKey = "half_open_circuits";
    private final String openCircuitsKey = "open_circuits";

    @Test
    public void testHalfOpenCircuits(){

        String c1 = "circuit_1";
        String c2 = "circuit_2";
        String c3 = "circuit_3";
        String c4 = "circuit_4";

        assertThat(jedis.exists(circuitInfoKey(c1)), is(false));
        assertThat(jedis.exists(circuitInfoKey(c2)), is(false));
        assertThat(jedis.exists(circuitInfoKey(c3)), is(false));
        assertThat(jedis.exists(circuitInfoKey(c4)), is(false));
        assertThat(jedis.exists(halfOpenCircuitsKey), is(false));
        assertThat(jedis.exists(openCircuitsKey), is(false));

        // prepare some test data
        addCircuitInfoEntry(c1, OPEN);
        addCircuitInfoEntry(c2, OPEN);
        addCircuitInfoEntry(c3, OPEN);
        addCircuitInfoEntry(c4, OPEN);

        jedis.sadd(halfOpenCircuitsKey, "someCircuitHash");
        jedis.sadd(halfOpenCircuitsKey, "anotherCircuitHash");

        assertState(c1, OPEN);
        assertState(c2, OPEN);
        assertState(c3, OPEN);
        assertState(c4, OPEN);

        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(2L));
        assertThat(jedis.scard(openCircuitsKey), equalTo(4L));

        Long count = (Long) evalScriptHalfOpenCircuits();
        assertThat(count, equalTo(4L));

        // assertions
        assertState(c1, HALF_OPEN);
        assertState(c2, HALF_OPEN);
        assertState(c3, HALF_OPEN);
        assertState(c4, HALF_OPEN);

        assertThat(jedis.exists(halfOpenCircuitsKey), is(true));
        assertThat(jedis.exists(openCircuitsKey), is(false)); // when the last entry was removed, the set was deleted

        assertThat(jedis.scard(halfOpenCircuitsKey), equalTo(6L));
        Set<String> halfOpenCircuits = jedis.smembers(halfOpenCircuitsKey);
        assertThat(halfOpenCircuits.contains("someCircuitHash"), is(true));
        assertThat(halfOpenCircuits.contains("anotherCircuitHash"), is(true));
        assertThat(halfOpenCircuits.contains("circuit_1"), is(true));
        assertThat(halfOpenCircuits.contains("circuit_2"), is(true));
        assertThat(halfOpenCircuits.contains("circuit_3"), is(true));
        assertThat(halfOpenCircuits.contains("circuit_4"), is(true));

        assertThat(jedis.scard(openCircuitsKey), equalTo(0L));
    }

    private Object evalScriptHalfOpenCircuits(){
        String script = readScript(QueueCircuitBreakerLuaScripts.HALFOPEN_CIRCUITS.getFilename());
        List<String> keys = Arrays.asList(
                halfOpenCircuitsKey,
                openCircuitsKey
        );

        List<String> arguments = Arrays.asList(
                circuitInfoKeyPrefix,
                circuitInfoKeySuffix
        );

        return jedis.eval(script, keys, arguments);
    }

    private String circuitInfoKey(String circuitHash){
        return circuitInfoKeyPrefix + circuitHash + circuitInfoKeySuffix;
    }

    private void assertState(String circuitHash, QueueCircuitState state){
        assertThat(jedis.hget(circuitInfoKey(circuitHash), FIELD_STATE).toLowerCase(), equalTo(state.name().toLowerCase()));
    }

    private void addCircuitInfoEntry(String circuitHash, QueueCircuitState state){
        jedis.hset(circuitInfoKey(circuitHash), FIELD_STATE, state.name().toLowerCase());
        if(OPEN == state){
            jedis.sadd(openCircuitsKey, circuitHash);
        } else if(HALF_OPEN == state){
            jedis.sadd(halfOpenCircuitsKey, circuitHash);
        }
    }
}
