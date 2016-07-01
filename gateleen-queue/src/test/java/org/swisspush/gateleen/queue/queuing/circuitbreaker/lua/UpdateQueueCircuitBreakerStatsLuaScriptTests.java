package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class UpdateQueueCircuitBreakerStatsLuaScriptTests extends AbstractLuaScriptTest {

    private final String key = "my_stats_set";

    @Test
    public void testUpdateQueueCircuitBreakerStatsWith(){
        assertThat(jedis.exists(key), is(false));
        assertThat(evalScriptUpdateQueueCircuitBreakerStats(key, "req_1", 1, 5), equalTo(luaResponse(0)));
        assertThat(evalScriptUpdateQueueCircuitBreakerStats(key, "req_2", 2, 5), equalTo(luaResponse(0)));
        assertThat(evalScriptUpdateQueueCircuitBreakerStats(key, "req_3", 3, 5), equalTo(luaResponse(0)));
        assertThat(jedis.zcard(key), equalTo(3L));
        assertThat(evalScriptUpdateQueueCircuitBreakerStats(key, "req_4", 4, 5), equalTo(luaResponse(0)));
        assertThat(evalScriptUpdateQueueCircuitBreakerStats(key, "req_5", 5, 5), equalTo(luaResponse(0)));
        assertThat(jedis.zcard(key), equalTo(5L));
        assertThat(evalScriptUpdateQueueCircuitBreakerStats(key, "req_6", 6, 5), equalTo(luaResponse(1)));
        assertThat(evalScriptUpdateQueueCircuitBreakerStats(key, "req_7", 7, 5), equalTo(luaResponse(1)));
        assertThat(jedis.zcard(key), equalTo(5L));
    }

    private String luaResponse(long removedCount){
        return "OK - Removed:" + removedCount;
    }

    private Object evalScriptUpdateQueueCircuitBreakerStats(String key, String uniqueRequestID,
                                                            long timestamp, long maxSampleCount) {
        String script = readScript(QueueCircuitBreakerLuaScripts.UPDATE_STATS.getFilename());
        List<String> keys = Collections.singletonList(key);
        List<String> arguments = Arrays.asList(
                uniqueRequestID,
                String.valueOf(timestamp),
                String.valueOf(maxSampleCount)
        );
        return jedis.eval(script, keys, arguments);
    }
}
