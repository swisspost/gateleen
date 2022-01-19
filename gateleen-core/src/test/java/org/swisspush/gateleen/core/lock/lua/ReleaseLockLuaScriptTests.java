package org.swisspush.gateleen.core.lock.lua;

import com.jayway.awaitility.Duration;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.testhelper.AbstractLuaScriptTest;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.List;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
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

        acquireLock(lock1, "token_1", 200);
        acquireLock(lock2, "token_2", 500);
        acquireLock(lock3, "token_3", 1000);

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

        acquireLock(lock1, "token_1", 200);
        acquireLock(lock2, "token_2", 500);
        acquireLock(lock3, "token_3", 1000);

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

        acquireLock(lock1, "token_1", 200);
        acquireLock(lock2, "token_2", 500);
        acquireLock(lock3, "token_3", 1000);

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        waitMaxUntilExpired(lock1, 250);
        waitMaxUntilExpired(lock2, 550);
        waitMaxUntilExpired(lock3, 1100);

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

        acquireLock(lock1, "token_1", 200);
        acquireLock(lock2, "token_2", 500);
        acquireLock(lock3, "token_3", 1000);

        assertThat(jedis.exists(lock1), is(true));
        assertThat(jedis.exists(lock2), is(true));
        assertThat(jedis.exists(lock3), is(true));

        waitMaxUntilExpired(lock1, 250);
        waitMaxUntilExpired(lock2, 550);
        waitMaxUntilExpired(lock3, 1100);

        assertThat(jedis.exists(lock1), is(false));
        assertThat(jedis.exists(lock2), is(false));
        assertThat(jedis.exists(lock3), is(false));

        assertThat("OK", equalTo(acquireLock(lock1, "token_X", 200)));
        assertThat("OK", equalTo(acquireLock(lock2, "token_Y", 500)));
        assertThat("OK", equalTo(acquireLock(lock3, "token_Z", 1000)));

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

    private String acquireLock(String lock, String token, long expireMs){
        return jedis.set(lock, token, SetParams.setParams().nx().px(expireMs));
    }

    private Object evalScriptReleaseLock(String lock, String token){
        String script = readScript(LockLuaScripts.LOCK_RELEASE.getFilename());
        List<String> keys = Collections.singletonList(lock);
        List<String> arguments = Collections.singletonList(token);
        return jedis.eval(script, keys, arguments);
    }

    private void waitMaxUntilExpired(String key, long expireMs){
        await().pollInterval(50, MILLISECONDS).atMost(new Duration(expireMs, MILLISECONDS)).until(() -> !jedis.exists(key));
    }
}
