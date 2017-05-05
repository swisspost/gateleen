package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.lua.AbstractLuaScriptTest;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState.CLOSED;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.util.QueueCircuitState.OPEN;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage.FIELD_FAILRATIO;
import static org.swisspush.gateleen.queue.queuing.circuitbreaker.impl.RedisQueueCircuitBreakerStorage.FIELD_STATE;

/**
 * Tests for the {@link QueueCircuitBreakerLuaScripts#UPDATE_CIRCUIT} lua script.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueCircuitBreakerUpdateStatsLuaScriptTests extends AbstractLuaScriptTest {

    private final String circuitInfoKey = "q:infos";
    private final String circuitSuccessKey = "q:success";
    private final String circuitFailureKey = "q:failure";
    private final String openCircuitsKey = "open_circuits";
    private final String allCircuitsKey = "all_circuits";

    @Test
    public void testCalculateErrorPercentage(){
        assertThat(jedis.exists(circuitInfoKey), is(false));
        assertThat(jedis.exists(circuitSuccessKey), is(false));
        assertThat(jedis.exists(circuitFailureKey), is(false));
        assertThat(jedis.exists(openCircuitsKey), is(false));
        assertThat(jedis.exists(allCircuitsKey), is(false));

        String update_success = "q:success";
        String update_fail = "q:failure";

        // adding 3 failing requests
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_1", "url_pattern", 0, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_2", "url_pattern", 1, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_3", "url_pattern", 2, 50, 10, 4, 10);

        // asserts
        assertThat(jedis.exists(circuitInfoKey), is(true));
        assertThat(jedis.exists(circuitSuccessKey), is(false));
        assertThat(jedis.exists(circuitFailureKey), is(true));
        assertStateAndErroPercentage(CLOSED, 100); // state should still be 'closed' because the minSampleThreshold (4) is not yet reached

        // add 1 successful request => now the minSampleThreshold is reached
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_4", "url_pattern", 3, 50, 10, 4, 10);

        assertThat(jedis.exists(circuitInfoKey), is(true));
        assertThat(jedis.exists(circuitSuccessKey), is(true));
        assertThat(jedis.exists(circuitFailureKey), is(true));
        assertThat(jedis.exists(openCircuitsKey), is(true));
        assertThat(jedis.exists(allCircuitsKey), is(true));
        assertStateAndErroPercentage(OPEN, 75);
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);

        // add 2 more successful requests => failurePercentage should drop
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_5", "url_pattern", 4, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_6", "url_pattern", 5, 50, 10, 4, 10);
        assertStateAndErroPercentage(OPEN, 50);
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);

        // add 1 more successful request => failurePercentage should drop and state should switch to 'half_open'
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_7", "url_pattern", 6, 50, 10, 4, 10);
        assertStateAndErroPercentage(OPEN, 42);
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);

        // add 1 more failing request => failurePercentage should raise again but state should remain 'half_open'
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_8", "url_pattern", 7, 50, 10, 4, 10);
        assertStateAndErroPercentage(OPEN, 50);
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);

        // add 3 more failing request => failurePercentage should raise again but state should remain 'half_open'
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_9", "url_pattern", 8, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_10", "url_pattern", 9, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_11", "url_pattern", 10, 50, 10, 4, 10);
        assertStateAndErroPercentage(OPEN, 63);
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);

        assertCircuit("url_pattern");
    }


    @Test
    public void testDontExceedMaxSetSize(){
        long maxSetSize = 10;

        assertSizeSizeNotExceedingLimit(circuitSuccessKey, maxSetSize);
        for (int i = 1; i <= 20; i++) {
            evalScriptUpdateQueueCircuitBreakerStats("q:success", "req_"+i, "url_pattern", i, 50, 10, 4, maxSetSize);
        }
        assertSizeSizeNotExceedingLimit(circuitSuccessKey, maxSetSize);

        // assert that the 'oldest' entries have been removed
        Set<String> remainingSetEntries = jedis.zrangeByScore(circuitSuccessKey, Long.MIN_VALUE, Long.MAX_VALUE);
        assertThat(remainingSetEntries.size(), equalTo(10));
        for(int i = 1; i <= 10; i++){
            assertThat(remainingSetEntries.contains("req_"+i), is(false));
        }
        for(int i = 11; i <= 20; i++){
            assertThat(remainingSetEntries.contains("req_"+i), is(true));
        }

        assertCircuit("url_pattern");
    }

    @Test
    public void testOnlyRespectEntriesWithinAgeRange(){
        String update_success = "q:success";
        String update_fail = "q:failure";
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_1", "url_pattern", 1, 50, 3, 1, 100);
        assertStateAndErroPercentage(CLOSED, 0);
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_2", "url_pattern", 2, 50, 3, 1, 100);
        assertStateAndErroPercentage(CLOSED, 0);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_3", "url_pattern", 3, 50, 3, 1, 100);
        assertStateAndErroPercentage(CLOSED, 33);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_4", "url_pattern", 4, 50, 3, 1, 100);
        assertStateAndErroPercentage(OPEN, 50);
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_5", "url_pattern", 5, 50, 3, 1, 100);
        assertStateAndErroPercentage(OPEN, 75); // req_1 is out of range by now
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_6", "url_pattern", 6, 50, 3, 1, 100);
        assertStateAndErroPercentage(OPEN, 100); // req_2 is out of range by now
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_7", "url_pattern", 7, 50, 3, 1, 100);
        assertStateAndErroPercentage(OPEN, 75); // req_3 is out of range by now
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_8", "url_pattern", 8, 50, 3, 1, 100);
        assertStateAndErroPercentage(OPEN, 50); // req_4 is out of range by now
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_9", "url_pattern", 9, 50, 3, 1, 100);
        assertStateAndErroPercentage(OPEN, 25); // req_5 is out of range by now
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_10", "url_pattern", 10, 50, 3, 1, 100);
        assertStateAndErroPercentage(OPEN, 0); // req_6 is out of range by now
        assertHashInOpenCircuitsSet("url_patternHash", 1);
        assertHashInAllCircuitsSet("url_patternHash", 1);

        assertCircuit("url_pattern");
    }

    private void assertStateAndErroPercentage(QueueCircuitState state, int percentage){
        assertThat(jedis.hget(circuitInfoKey, FIELD_STATE).toLowerCase(), equalTo(state.name().toLowerCase()));
        String percentageAsString = jedis.hget(circuitInfoKey, FIELD_FAILRATIO);
        assertThat(Integer.valueOf(percentageAsString), equalTo(percentage));
    }

    private void assertHashInOpenCircuitsSet(String hash, long amountOfOpenCircuits){
        Set<String> openCircuits = jedis.smembers(openCircuitsKey);
        assertThat(openCircuits.contains(hash), is(true));
        assertThat(jedis.scard(openCircuitsKey), equalTo(amountOfOpenCircuits));
    }

    private void assertHashInAllCircuitsSet(String hash, long amountOfCircuits){
        Set<String> openCircuits = jedis.smembers(allCircuitsKey);
        assertThat(openCircuits.contains(hash), is(true));
        assertThat(jedis.scard(allCircuitsKey), equalTo(amountOfCircuits));
    }

    private void assertCircuit(String circuit){
        assertThat(jedis.hget(circuitInfoKey, "circuit"), equalTo(circuit));
    }

    private void assertSizeSizeNotExceedingLimit(String setKey, long maxSetSize){
        assertThat(jedis.zcard(setKey) <= maxSetSize, is(true));
    }

    private Object evalScriptUpdateQueueCircuitBreakerStats(String circuitKeyToUpdate, String uniqueRequestID,
                                                            String circuit, long timestamp, int errorThresholdPercentage,
                                                            long entriesMaxAgeMS, long minQueueSampleCount, long maxQueueSampleCount) {

        String script = readScript(QueueCircuitBreakerLuaScripts.UPDATE_CIRCUIT.getFilename());
        List<String> keys = Arrays.asList(
                circuitInfoKey,
                circuitSuccessKey,
                circuitFailureKey,
                circuitKeyToUpdate,
                openCircuitsKey,
                allCircuitsKey
        );

        List<String> arguments = Arrays.asList(
                uniqueRequestID,
                circuit,
                circuit+"Hash",
                String.valueOf(timestamp),
                String.valueOf(errorThresholdPercentage),
                String.valueOf(entriesMaxAgeMS),
                String.valueOf(minQueueSampleCount),
                String.valueOf(maxQueueSampleCount)
        );
        return jedis.eval(script, keys, arguments);
    }
}
