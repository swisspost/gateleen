package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;
import org.swisspush.gateleen.testhelper.AbstractLuaScriptTest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState.*;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage.*;

/**
 * Tests for the {@link QueueCircuitBreakerLuaScripts#ALL_CIRCUITS} lua script.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerGetAllCircuitsLuaScriptTests extends AbstractLuaScriptTest {

    private final String circuitInfoKeyPrefix = "q:";
    private final String circuitInfoKeySuffix = ":infos";
    private final String allCircuitsKey = "all_circuits";

    @Test
    public void testGetAllCircuits(){
        assertThat(jedis.exists(allCircuitsKey), is(false));

        // prepare some test data
        String hash1 = "hash_1";
        String hash2 = "hash_2";
        String hash3 = "hash_3";

        writeQueueCircuit(hash1, HALF_OPEN, "/path/to/hash_1", 60);
        writeQueueCircuit(hash2, CLOSED, "/path/to/hash_2", 20);
        writeQueueCircuit(hash3, OPEN, "/path/to/hash_3", 99);

        assertThat(jedis.hget(circuitInfoKey(hash1), FIELD_STATE).toLowerCase(), equalTo(HALF_OPEN.name().toLowerCase()));
        assertThat(jedis.hget(circuitInfoKey(hash1), FIELD_CIRCUIT), equalTo("/path/to/hash_1"));
        assertThat(jedis.hget(circuitInfoKey(hash1), FIELD_FAILRATIO), equalTo("60"));

        assertThat(jedis.hget(circuitInfoKey(hash2), FIELD_STATE).toLowerCase(), equalTo(CLOSED.name().toLowerCase()));
        assertThat(jedis.hget(circuitInfoKey(hash2), FIELD_CIRCUIT), equalTo("/path/to/hash_2"));
        assertThat(jedis.hget(circuitInfoKey(hash2), FIELD_FAILRATIO), equalTo("20"));

        assertThat(jedis.hget(circuitInfoKey(hash3), FIELD_STATE).toLowerCase(), equalTo(OPEN.name().toLowerCase()));
        assertThat(jedis.hget(circuitInfoKey(hash3), FIELD_CIRCUIT), equalTo("/path/to/hash_3"));
        assertThat(jedis.hget(circuitInfoKey(hash3), FIELD_FAILRATIO), equalTo("99"));

        assertThat(jedis.scard(allCircuitsKey), equalTo(3L));

        JsonObject result = new JsonObject(evalScriptGetAllCircuits().toString());

        // assertions
        assertJsonObjectContents(result, hash1, "half_open", "/path/to/hash_1", 60);
        assertJsonObjectContents(result, hash2, "closed", "/path/to/hash_2", 20);
        assertJsonObjectContents(result, hash3, "open", "/path/to/hash_3", 99);
    }

    private void assertJsonObjectContents(JsonObject result, String hash, String status, String circuit, int failRatio){
        assertThat(result.containsKey(hash), is(true));
        assertThat(result.getJsonObject(hash).containsKey("status"), is(true));
        assertThat(result.getJsonObject(hash).getString("status"), equalTo(status));
        assertThat(result.getJsonObject(hash).containsKey("infos"), is(true));
        assertThat(result.getJsonObject(hash).getJsonObject("infos").containsKey("circuit"), is(true));
        assertThat(result.getJsonObject(hash).getJsonObject("infos").getString("circuit"), equalTo(circuit));
        assertThat(result.getJsonObject(hash).getJsonObject("infos").containsKey("failRatio"), is(true));
        assertThat(result.getJsonObject(hash).getJsonObject("infos").getInteger("failRatio"), equalTo(failRatio));
    }

    private Object evalScriptGetAllCircuits(){
        String script = readScript(QueueCircuitBreakerLuaScripts.ALL_CIRCUITS.getFilename());
        List<String> keys = Collections.singletonList(allCircuitsKey);
        List<String> arguments = Arrays.asList(circuitInfoKeyPrefix, circuitInfoKeySuffix);
        return jedis.eval(script, keys, arguments);
    }

    private void writeQueueCircuit(String circuitHash, QueueCircuitState state, String circuit, int failPercentage){
        writeQueueCircuitField(circuitHash, FIELD_STATE, state.name().toLowerCase());
        writeQueueCircuitField(circuitHash, FIELD_CIRCUIT, circuit);
        writeQueueCircuitField(circuitHash, FIELD_FAILRATIO, String.valueOf(failPercentage));
        jedis.sadd(allCircuitsKey, circuitHash);
    }

    private void writeQueueCircuitField(String circuitHash, String field, String value){
        jedis.hset(circuitInfoKeyPrefix + circuitHash + circuitInfoKeySuffix, field, value);
    }

    private String circuitInfoKey(String circuitHash){
        return circuitInfoKeyPrefix + circuitHash + circuitInfoKeySuffix;
    }
}
