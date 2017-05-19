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
import static org.junit.Assert.assertThat;
import static org.swisspush.gateleen.core.lock.impl.RedisBasedLock.STORAGE_PREFIX;

/**
 * Tests for the {@link LockLuaScripts#LOCK_RELEASE} lua script.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ReleaseLockLuaScriptTests extends AbstractLuaScriptTest {

    @Test
    public void testReleaseNotExistingLocks(){

        String lock1 = buildLockKey("lock_1");
        String lock2 = buildLockKey("lock_2");
        String lock3 = buildLockKey("lock_3");

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        assertThat(0L, equalTo(evalScriptReleaseLock(lock1, "token_1")));
        assertThat(0L, equalTo(evalScriptReleaseLock(lock2, "token_2")));
        assertThat(0L, equalTo(evalScriptReleaseLock(lock3, "token_3")));

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));
    }

    @Test
    public void testReleaseLocksWithCorrectToken(){

        String lock1 = buildLockKey("lock_1");
        String lock2 = buildLockKey("lock_2");
        String lock3 = buildLockKey("lock_3");

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        evalScriptAcquireLock(lock1, "token_1", 200);
        evalScriptAcquireLock(lock2, "token_2", 500);
        evalScriptAcquireLock(lock3, "token_3", 1000);

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        assertThat(1L, equalTo(evalScriptReleaseLock(lock1, "token_1")));
        assertThat(1L, equalTo(evalScriptReleaseLock(lock2, "token_2")));
        assertThat(1L, equalTo(evalScriptReleaseLock(lock3, "token_3")));

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));
    }

    @Test
    public void testReleaseLocksWithWrongToken(){

        String lock1 = buildLockKey("lock_1");
        String lock2 = buildLockKey("lock_2");
        String lock3 = buildLockKey("lock_3");

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        evalScriptAcquireLock(lock1, "token_1", 200);
        evalScriptAcquireLock(lock2, "token_2", 500);
        evalScriptAcquireLock(lock3, "token_3", 1000);

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        assertThat(0L, equalTo(evalScriptReleaseLock(lock1, "token_X")));
        assertThat(0L, equalTo(evalScriptReleaseLock(lock2, "token_Y")));
        assertThat(0L, equalTo(evalScriptReleaseLock(lock3, "token_Z")));

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));
    }

    @Test
    public void testReleaseExpiredLocks(){

        String lock1 = buildLockKey("lock_1");
        String lock2 = buildLockKey("lock_2");
        String lock3 = buildLockKey("lock_3");

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        evalScriptAcquireLock(lock1, "token_1", 200);
        evalScriptAcquireLock(lock2, "token_2", 500);
        evalScriptAcquireLock(lock3, "token_3", 1000);

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        await().pollInterval(2, MILLISECONDS).atMost(new Duration(250, MILLISECONDS)).until(() -> !jedis.exists(lock1));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(550, MILLISECONDS)).until(() -> !jedis.exists(lock2));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(1100, MILLISECONDS)).until(() -> !jedis.exists(lock3));

        assertThat(0L, equalTo(evalScriptReleaseLock(lock1, "token_1")));
        assertThat(0L, equalTo(evalScriptReleaseLock(lock2, "token_2")));
        assertThat(0L, equalTo(evalScriptReleaseLock(lock3, "token_3")));

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));
    }

    @Test
    public void testReleaseLockRespectingOwnership(){

        String lock1 = buildLockKey("lock_1");
        String lock2 = buildLockKey("lock_2");
        String lock3 = buildLockKey("lock_3");

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        evalScriptAcquireLock(lock1, "token_1", 200);
        evalScriptAcquireLock(lock2, "token_2", 500);
        evalScriptAcquireLock(lock3, "token_3", 1000);

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        await().pollInterval(2, MILLISECONDS).atMost(new Duration(250, MILLISECONDS)).until(() -> !jedis.exists(lock1));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(550, MILLISECONDS)).until(() -> !jedis.exists(lock2));
        await().pollInterval(2, MILLISECONDS).atMost(new Duration(1100, MILLISECONDS)).until(() -> !jedis.exists(lock3));

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        assertThat("OK", equalTo(evalScriptAcquireLock(lock1, "token_X", 200)));
        assertThat("OK", equalTo(evalScriptAcquireLock(lock2, "token_Y", 500)));
        assertThat("OK", equalTo(evalScriptAcquireLock(lock3, "token_Z", 1000)));

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        assertThat(0L, equalTo(evalScriptReleaseLock(lock1, "token_1")));
        assertThat(0L, equalTo(evalScriptReleaseLock(lock2, "token_2")));
        assertThat(0L, equalTo(evalScriptReleaseLock(lock3, "token_3")));

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        assertThat(1L, equalTo(evalScriptReleaseLock(lock1, "token_X")));
        assertThat(1L, equalTo(evalScriptReleaseLock(lock2, "token_Y")));
        assertThat(1L, equalTo(evalScriptReleaseLock(lock3, "token_Z")));

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

    private Object evalScriptReleaseLock(String lock, String token){
        String script = readScript(LockLuaScripts.LOCK_RELEASE.getFilename());
        List<String> keys = Collections.singletonList(lock);
        List<String> arguments = Collections.singletonList(token);
        return jedis.eval(script, keys, arguments);
    }
}
