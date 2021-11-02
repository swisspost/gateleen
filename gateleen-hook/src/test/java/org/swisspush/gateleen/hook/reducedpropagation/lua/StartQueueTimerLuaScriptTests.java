package org.swisspush.gateleen.hook.reducedpropagation.lua;

import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.testhelper.AbstractLuaScriptTest;
import redis.clients.jedis.Tuple;

import java.util.*;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for the {@link ReducedPropagationLuaScripts#START_QUEUE_TIMER} lua script.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class StartQueueTimerLuaScriptTests extends AbstractLuaScriptTest {

    private final String queuesTimersSetKey = "q:queuesTimers";

    @Test
    public void testStartQueueTimerNoDuplicates(){
        assertThat(jedis.exists(queuesTimersSetKey), is(false));

        assertThat(evalScriptStartQueueTimer("queue_1", 10), equalTo(1L));
        assertThat(evalScriptStartQueueTimer("queue_1", 10), equalTo(0L));
        assertThat(evalScriptStartQueueTimer("queue_1", 20), equalTo(0L));
        assertThat(evalScriptStartQueueTimer("queue_1", 30), equalTo(0L));

        assertThat(jedis.exists(queuesTimersSetKey), is(true));

        Set<Tuple> expectedTuples = new HashSet<>();
        expectedTuples.add(new Tuple("queue_1", 10.0));

        assertQueuesTimersSetContent(1, expectedTuples);
    }

    @Test
    public void testStartQueueTimerMultipleQueues(){
        assertThat(jedis.exists(queuesTimersSetKey), is(false));

        assertThat(evalScriptStartQueueTimer("queue_1", 40), equalTo(1L));
        assertThat(evalScriptStartQueueTimer("queue_1", 45), equalTo(0L));
        assertThat(evalScriptStartQueueTimer("queue_1", 46), equalTo(0L));
        assertThat(evalScriptStartQueueTimer("queue_2", 10), equalTo(1L));
        assertThat(evalScriptStartQueueTimer("queue_3", 20), equalTo(1L));
        assertThat(evalScriptStartQueueTimer("queue_3", 21), equalTo(0L));
        assertThat(evalScriptStartQueueTimer("queue_3", 22), equalTo(0L));

        assertThat(jedis.exists(queuesTimersSetKey), is(true));

        Set<Tuple> expectedTuples = new HashSet<>();
        expectedTuples.add(new Tuple("queue_1", 40.0));
        expectedTuples.add(new Tuple("queue_2", 10.0));
        expectedTuples.add(new Tuple("queue_3", 20.0));

        assertQueuesTimersSetContent(3, expectedTuples);

        assertThat(evalScriptStartQueueTimer("queue_3", 50), equalTo(0L));
        assertThat(evalScriptStartQueueTimer("queue_3", 60), equalTo(0L));
        assertThat(evalScriptStartQueueTimer("queue_3", 70), equalTo(0L));

        assertQueuesTimersSetContent(3, expectedTuples);
    }

    @Test
    public void testStartQueueTimerCaseSensitiv(){
        assertThat(jedis.exists(queuesTimersSetKey), is(false));

        assertThat(evalScriptStartQueueTimer("queue_1", 40), equalTo(1L));
        assertThat(evalScriptStartQueueTimer("QUEUE_1", 10), equalTo(1L));

        assertThat(jedis.exists(queuesTimersSetKey), is(true));

        Set<Tuple> expectedTuples = new HashSet<>();
        expectedTuples.add(new Tuple("queue_1", 40.0));
        expectedTuples.add(new Tuple("QUEUE_1", 10.0));

        assertQueuesTimersSetContent(2, expectedTuples);

        assertThat(evalScriptStartQueueTimer("queue_1", 50), equalTo(0L));
        assertThat(evalScriptStartQueueTimer("QUEUE_1", 60), equalTo(0L));

        assertQueuesTimersSetContent(2, expectedTuples);
    }

    private void assertQueuesTimersSetContent(int nbrOfEntries, Set<Tuple> expectedTuples){
        Set<Tuple> tuples = jedis.zrangeByScoreWithScores(queuesTimersSetKey, "-inf", "+inf");
        assertThat(tuples.size(), equalTo(nbrOfEntries));
        assertThat(tuples, equalTo(expectedTuples));
    }

    private Object evalScriptStartQueueTimer(String queue, long expireTS){
        String script = readScript(ReducedPropagationLuaScripts.START_QUEUE_TIMER.getFilename());
        List<String> keys = Collections.singletonList(queuesTimersSetKey);
        List<String> arguments = Arrays.asList(queue, String.valueOf(expireTS));
        return jedis.eval(script, keys, arguments);
    }
}
