package org.swisspush.gateleen.core.util;

import io.vertx.core.Future;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lock.Lock;

import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;

/**
 * Tests for the {@link LockUtil} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class LockUtilTest {

    private Lock lock;
    private Logger log;

    @Before
    public void setUp(){
        lock = Mockito.mock(Lock.class);
        log = Mockito.mock(Logger.class);
    }

    @Test
    public void testAcquireLockWithoutLockImplementationDefined(TestContext context) {
        Async async = context.async();
        LockUtil.acquireLock(null, "someLock", "someToken", 100, log).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            Mockito.verify(log, Mockito.times(1)).info(Matchers.eq("No lock implementation defined, going to pretend like we got the lock"));
            async.complete();
        });
    }

    @Test
    public void testAcquireLockSuccess(TestContext context) {
        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.succeededFuture(Boolean.TRUE));
        Async async = context.async();
        LockUtil.acquireLock(lock, "someLock", "someToken", 100, log).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            Mockito.verify(log, Mockito.times(1)).debug(Matchers.eq("Trying to acquire lock 'someLock' with token 'someToken' and expiry 100ms"));
            Mockito.verify(log, Mockito.times(1)).debug(Matchers.eq("Acquired lock 'someLock' with token 'someToken'"));
            async.complete();
        });
    }

    @Test
    public void testAcquireLockFail(TestContext context) {
        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.succeededFuture(Boolean.FALSE));
        Async async = context.async();
        LockUtil.acquireLock(lock, "someLock", "someToken", 100, log).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result());
            Mockito.verify(log, Mockito.times(1)).debug(Matchers.eq("Trying to acquire lock 'someLock' with token 'someToken' and expiry 100ms"));
            async.complete();
        });
    }

    @Test
    public void testAcquireLockError(TestContext context) {
        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.failedFuture("Booom"));
        Async async = context.async();
        LockUtil.acquireLock(lock, "someLock", "someToken", 100, log).setHandler(event -> {
            context.assertFalse(event.succeeded());
            context.assertEquals("Booom", event.cause().getMessage());
            async.complete();
        });
    }

    @Test
    public void testReleaseLockWithoutLockImplementationDefined(TestContext context) {
        LockUtil.releaseLock(null, "someLock", "someToken", log);
        Mockito.verify(log, Mockito.timeout(100).times(1)).info(Matchers.eq("No lock implementation defined, going to pretend like we released the lock"));
    }

    @Test
    public void testReleaseLockSuccess(TestContext context) {
        Mockito.when(lock.releaseLock(anyString(), anyString())).thenReturn(Future.succeededFuture(Boolean.TRUE));
        LockUtil.releaseLock(lock, "someLock", "someToken", log);
        Mockito.verify(log, Mockito.times(1)).debug(Matchers.eq("Trying to release lock 'someLock' with token 'someToken'"));
        Mockito.verify(log, Mockito.times(1)).debug(Matchers.eq("Released lock 'someLock' with token 'someToken'"));
    }

    @Test
    public void testReleaseLockFail(TestContext context) {
        Mockito.when(lock.releaseLock(anyString(), anyString())).thenReturn(Future.succeededFuture(Boolean.FALSE));
        LockUtil.releaseLock(lock, "someLock", "someToken", log);
        Mockito.verify(log, Mockito.times(1)).debug(Matchers.eq("Trying to release lock 'someLock' with token 'someToken'"));
    }

    @Test
    public void testReleaseLockError(TestContext context) {
        Mockito.when(lock.releaseLock(anyString(), anyString())).thenReturn(Future.failedFuture("Booom"));
        LockUtil.releaseLock(lock, "someLock", "someToken", log);
        Mockito.verify(log, Mockito.times(1)).debug(Matchers.eq("Trying to release lock 'someLock' with token 'someToken'"));
        Mockito.verify(log, Mockito.times(1)).error(Matchers.eq("Could not release lock 'someLock'. Message: Booom"));
    }
}
