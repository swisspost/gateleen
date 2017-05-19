package org.swisspush.gateleen.core.lock.lua;

import com.jayway.awaitility.Duration;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.lua.AbstractLuaScriptTest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.swisspush.gateleen.core.lock.impl.RedisBasedLock.STORAGE_PREFIX;

/**
 * Tests for the {@link LockLuaScripts#LOCK_ACQUIRE} lua script.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class AcquireLockLuaScriptTests extends AbstractLuaScriptTest {

    @Test
    public void testAcquireLockAndAutomaticallyExpire(){

        String lock1 = buildLockKey("lock_1");
        String lock2 = buildLockKey("lock_2");
        String lock3 = buildLockKey("lock_3");

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        evalScriptAcquireLock(lock1, "token_1", 200);
        evalScriptAcquireLock(lock2, "token_2", 500);
        evalScriptAcquireLock(lock3, "token_3", 1000);

        // assertions
        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        assertThat(jedis.get(lock1), equalTo("token_1"));
        assertThat(jedis.get(lock2), equalTo("token_2"));
        assertThat(jedis.get(lock3), equalTo("token_3"));

        assertThat(jedis.pttl(lock1), lessThanOrEqualTo(200L));
        assertThat(jedis.pttl(lock2), lessThanOrEqualTo(500L));
        assertThat(jedis.pttl(lock3), lessThanOrEqualTo(1000L));

        await().pollInterval(2, MILLISECONDS).atMost(new Duration(250, MILLISECONDS)).until(() -> !jedis.exists(lock1));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(550, MILLISECONDS)).until(() -> !jedis.exists(lock2));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(1100, MILLISECONDS)).until(() -> !jedis.exists(lock3));

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));
    }

    @Test
    public void testAcquireLockReturnValue(){
        String lock1 = buildLockKey("lock_1");
        String lock2 = buildLockKey("lock_2");
        String lock3 = buildLockKey("lock_3");

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        String returnLock1 = (String) evalScriptAcquireLock(lock1, "token_1", 200);
        String returnLock2 = (String) evalScriptAcquireLock(lock2, "token_2", 500);
        String returnLock3 = (String) evalScriptAcquireLock(lock3, "token_3", 1000);

        assertThat("OK", equalTo(returnLock1));
        assertThat("OK", equalTo(returnLock2));
        assertThat("OK", equalTo(returnLock3));

        assertNull(evalScriptAcquireLock(lock1, "token_1", 100));
        assertNull(evalScriptAcquireLock(lock2, "token_2", 200));
        assertNull(evalScriptAcquireLock(lock3, "token_3", 500));

        assertThat(jedis.pttl(lock1), greaterThan(100L));
        assertThat(jedis.pttl(lock2), greaterThan(200L));
        assertThat(jedis.pttl(lock3), greaterThan(500L));

        assertNull(evalScriptAcquireLock(lock1, "token_A", 20));
        assertNull(evalScriptAcquireLock(lock2, "token_B", 40));
        assertNull(evalScriptAcquireLock(lock3, "token_C", 60));

        assertThat(jedis.get(lock1), equalTo("token_1"));
        assertThat(jedis.get(lock2), equalTo("token_2"));
        assertThat(jedis.get(lock3), equalTo("token_3"));

        assertThat(jedis.pttl(lock1), greaterThan(20L));
        assertThat(jedis.pttl(lock2), greaterThan(40L));
        assertThat(jedis.pttl(lock3), greaterThan(60L));

        await().pollInterval(2, MILLISECONDS).atMost(new Duration(200, MILLISECONDS)).until(() -> !jedis.exists(lock1));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(500, MILLISECONDS)).until(() -> !jedis.exists(lock2));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(1000, MILLISECONDS)).until(() -> !jedis.exists(lock3));

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        assertThat("OK", equalTo(evalScriptAcquireLock(lock1, "token_X", 50)));
        assertThat("OK", equalTo(evalScriptAcquireLock(lock2, "token_Y", 80)));
        assertThat("OK", equalTo(evalScriptAcquireLock(lock3, "token_Z", 100)));

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        assertThat(jedis.get(lock1), equalTo("token_X"));
        assertThat(jedis.get(lock2), equalTo("token_Y"));
        assertThat(jedis.get(lock3), equalTo("token_Z"));

        assertThat(jedis.pttl(lock1), lessThanOrEqualTo(50L));
        assertThat(jedis.pttl(lock2), lessThanOrEqualTo(80L));
        assertThat(jedis.pttl(lock3), lessThanOrEqualTo(100L));

        await().pollInterval(2, MILLISECONDS).atMost(new Duration(50, MILLISECONDS)).until(() -> !jedis.exists(lock1));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(80, MILLISECONDS)).until(() -> !jedis.exists(lock2));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(100, MILLISECONDS)).until(() -> !jedis.exists(lock3));

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));
    }

    private String buildLockKey(String lock){
        return STORAGE_PREFIX + lock;
    }

    private Object evalScriptAcquireLock(String lock, String token, long expireMs){
        String script = readScript(LockLuaScripts.LOCK_ACQUIRE.getFilename());
        List<String> keys = Collections.singletonList(lock);
        List<String> arguments = Arrays.asList(token, String.valueOf(expireMs));
        return jedis.eval(script, keys, arguments);
    }
}
