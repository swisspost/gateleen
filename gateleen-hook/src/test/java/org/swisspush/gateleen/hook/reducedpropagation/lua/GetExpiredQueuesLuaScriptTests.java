package org.swisspush.gateleen.hook.reducedpropagation.lua;

import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.lua.AbstractLuaScriptTest;
import redis.clients.jedis.Tuple;

import java.util.*;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * Tests for the {@link ReducedPropagationLuaScripts#START_QUEUE_TIMER} lua script.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class GetExpiredQueuesLuaScriptTests extends AbstractLuaScriptTest {

    private final String queuesTimersSetKey = "q:queuesTimers";

    @Test
    public void testGetExpiredQueuesEmptySet(){
        assertThat(jedis.exists(queuesTimersSetKey), is(false));
        assertResult(evalScriptGetExpiredQueues(0), Collections.emptyList());
        assertResult(evalScriptGetExpiredQueues(5), Collections.emptyList());
        assertResult(evalScriptGetExpiredQueues(10), Collections.emptyList());
        assertThat(jedis.exists(queuesTimersSetKey), is(false));

        assertRemainingQueues(Collections.emptySet(), 0);
    }

    @Test
    public void testGetExpiredQueuesNoExpiredQueues(){
        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(0L));

        addQueues("queue_1", 10);
        addQueues("queue_2", 20);
        addQueues("queue_3", 30);

        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(3L));
        assertResult(evalScriptGetExpiredQueues(5), Collections.emptyList());
        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(3L));

        Set<Tuple> expectedRemaining = new HashSet<>();
        expectedRemaining.add(new Tuple("queue_1", 10.0));
        expectedRemaining.add(new Tuple("queue_2", 20.0));
        expectedRemaining.add(new Tuple("queue_3", 30.0));
        assertRemainingQueues(expectedRemaining, 3);
    }

    @Test
    public void testGetExpiredQueues(){
        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(0L));

        addQueues("queue_1", 10);
        addQueues("queue_2", 20);
        addQueues("queue_3", 30);

        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(3L));
        assertResult(evalScriptGetExpiredQueues(25), Arrays.asList("queue_1", "queue_2"));
        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(1L));

        Set<Tuple> expectedRemaining = new HashSet<>();
        expectedRemaining.add(new Tuple("queue_3", 30.0));
        assertRemainingQueues(expectedRemaining, 1);
    }

    @Test
    public void testGetExpiredQueuesEdgeCase(){
        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(0L));

        addQueues("queue_1", 9);
        addQueues("queue_2", 10);
        addQueues("queue_3", 11);
        addQueues("queue_4", 20);
        addQueues("queue_5", 30);

        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(5L));
        assertResult(evalScriptGetExpiredQueues(10), Arrays.asList("queue_1", "queue_2"));
        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(3L));

        Set<Tuple> expectedRemaining = new HashSet<>();
        expectedRemaining.add(new Tuple("queue_3", 11.0));
        expectedRemaining.add(new Tuple("queue_4", 20.0));
        expectedRemaining.add(new Tuple("queue_5", 30.0));
        assertRemainingQueues(expectedRemaining, 3);
    }

    @Test
    public void testGetExpiredQueuesAllExpired(){
        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(0L));

        addQueues("queue_1", 10);
        addQueues("queue_2", 20);
        addQueues("queue_3", 30);

        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(3L));
        assertResult(evalScriptGetExpiredQueues(50), Arrays.asList("queue_1", "queue_2", "queue_3"));
        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(0L));

        assertRemainingQueues(Collections.emptySet(), 0);
        assertThat(jedis.zcard(queuesTimersSetKey), equalTo(0L));
    }

    private void addQueues(String queue, long expireTS){
        jedis.zadd(queuesTimersSetKey, expireTS, queue);
    }

    private void assertResult(ArrayList result, List<String> expiredQueues){
        assertThat(result.size(), equalTo(expiredQueues.size()));
        assertThat(expiredQueues, equalTo(result));
    }

    private void assertRemainingQueues(Set<Tuple> expectedRemaining, int nbrRemainingEntries){
        Set<Tuple> tuples = jedis.zrangeByScoreWithScores(queuesTimersSetKey, "-inf", "+inf");
        assertThat(tuples.size(), equalTo(nbrRemainingEntries));
        assertThat(tuples, equalTo(expectedRemaining));
    }

    private ArrayList evalScriptGetExpiredQueues(long currentTS){
        String script = readScript(ReducedPropagationLuaScripts.GET_EXPIRED_QUEUES.getFilename());
        List<String> keys = Collections.singletonList(queuesTimersSetKey);
        List<String> arguments = Collections.singletonList(String.valueOf(currentTS));
        return (ArrayList) jedis.eval(script, keys, arguments);
    }
}
