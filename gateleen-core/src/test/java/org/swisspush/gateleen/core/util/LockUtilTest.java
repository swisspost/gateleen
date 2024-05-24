package org.swisspush.gateleen.core.util;

import io.vertx.core.Future;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lock.Lock;

import static org.mockito.ArgumentMatchers.*;
import static org.swisspush.gateleen.core.exception.GateleenExceptionFactory.newGateleenWastefulExceptionFactory;

/**
 * Tests for the {@link LockUtil} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class LockUtilTest {

    private Lock lock;
    private Logger log;
    private LockUtil lockUtil;

    @Before
    public void setUp(){
        lock = Mockito.mock(Lock.class);
        log = Mockito.mock(Logger.class);
        lockUtil = new LockUtil(newGateleenWastefulExceptionFactory());
    }

    @Test
    public void testAcquireLockWithoutLockImplementationDefined(TestContext context) {
        Async async = context.async();
        LockUtil.acquireLock(null, "someLock", "someToken", 100, log).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            Mockito.verify(log, Mockito.times(1)).info(eq("No lock implementation defined, going to pretend like we got the lock"));
            async.complete();
        });
    }

    @Test
    public void testAcquireLockSuccess(TestContext context) {
        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.succeededFuture(Boolean.TRUE));
        Async async = context.async();
        LockUtil.acquireLock(lock, "someLock", "someToken", 100, log).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            Mockito.verify(log, Mockito.times(1)).debug(eq("Trying to acquire lock '{}' with token '{}' and expiry {}ms"), eq("someLock"), eq("someToken"), eq(100L));
            Mockito.verify(log, Mockito.times(1)).debug(eq("Acquired lock '{}' with token '{}'"), eq("someLock"), eq("someToken"));
            async.complete();
        });
    }

    @Test
    public void testAcquireLockFail(TestContext context) {
        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.succeededFuture(Boolean.FALSE));
        Async async = context.async();
        LockUtil.acquireLock(lock, "someLock", "someToken", 100, log).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result());
            Mockito.verify(log, Mockito.times(1)).debug(eq("Trying to acquire lock '{}' with token '{}' and expiry {}ms"), eq("someLock"), eq("someToken"), eq(100L));
            async.complete();
        });
    }

    @Test
    public void testAcquireLockError(TestContext context) {
        Mockito.when(lock.acquireLock(anyString(), anyString(), anyLong())).thenReturn(Future.failedFuture("Booom"));
        Async async = context.async();
        LockUtil.acquireLock(lock, "someLock", "someToken", 100, log).onComplete(event -> {
            context.assertFalse(event.succeeded());
            context.assertEquals("Booom", event.cause().getMessage());
            async.complete();
        });
    }

    @Test
    public void testReleaseLockWithoutLockImplementationDefined(TestContext context) {
        lockUtil.releaseLock(null, "someLock", "someToken", log);
        Mockito.verify(log, Mockito.timeout(100).times(1)).info(eq("No lock implementation defined, going to pretend like we released the lock"));
    }

    @Test
    public void testReleaseLockSuccess(TestContext context) {
        Mockito.when(lock.releaseLock(anyString(), anyString())).thenReturn(Future.succeededFuture(Boolean.TRUE));
        lockUtil.releaseLock(lock, "someLock", "someToken", log);
        Mockito.verify(log, Mockito.times(1)).debug(eq("Trying to release lock '{}' with token '{}'"), eq("someLock"), eq("someToken"));
        Mockito.verify(log, Mockito.times(1)).debug(eq("Released lock '{}' with token '{}'"), eq("someLock"), eq("someToken"));
    }

    @Test
    public void testReleaseLockFail(TestContext context) {
        Mockito.when(lock.releaseLock(anyString(), anyString())).thenReturn(Future.succeededFuture(Boolean.FALSE));
        lockUtil.releaseLock(lock, "someLock", "someToken", log);
        Mockito.verify(log, Mockito.times(1)).debug(eq("Trying to release lock '{}' with token '{}'"), eq("someLock"), eq("someToken"));
    }

    @Test
    public void testReleaseLockError(TestContext context) {
        Mockito.when(lock.releaseLock(anyString(), anyString())).thenReturn(Future.failedFuture("Booom"));
        lockUtil.releaseLock(lock, "someLock", "someToken", log);
        Mockito.verify(log, Mockito.times(1)).debug(eq("Trying to release lock '{}' with token '{}'"), eq("someLock"), eq("someToken"));
        Mockito.verify(log, Mockito.times(1)).error(eq("Could not release lock '{}'."), eq("someLock"), isA(Throwable.class));
    }
}
