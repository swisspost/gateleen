package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.slf4j.Logger;


/**
 * Tests for the {@link QueueRetryUtil} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class QueueRetryUtilTest {

    private Logger logger;

    @Before
    public void setUp(){
        logger = Mockito.mock(Logger.class);
    }

    @Test
    public void testNoQueueRetryConfig(TestContext context){
        context.assertTrue(QueueRetryUtil.retryQueueItem(new CaseInsensitiveHeaders(), 0, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(new CaseInsensitiveHeaders(), 100, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(new CaseInsensitiveHeaders(), 404, logger));
    }

    @Test
    public void testInvalidQueueRetryConfig(TestContext context){
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-400", "-5"), 503, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-400", "-5"), 400, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-4xx", "-5"), 400, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-40x", "0"), 400, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-foobar", "-5"), 400, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-foobar", "0"), 400, logger));

        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-400", "boom"), 400, logger));
        Mockito.verify(logger, Mockito.times(1)).warn(
                Mockito.eq("Invalid value for queue retry configuration: {}"),
                Mockito.eq("boom")
        );
    }

    @Test
    public void testValidQueueRetryConfig(TestContext context){
        // should retry
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-400", "3"), 503, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-400", "0"), 503, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-400", "2"), 400, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-4xx", "1"), 400, logger));
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-400", "0"), 503, logger));

        // should not retry
        context.assertFalse(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-400", "0"), 400, logger));
        context.assertFalse(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-4xx", "0"), 400, logger));
        context.assertFalse(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-4xx", "0"), 405, logger));
        context.assertFalse(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-5xx", "0"), 503, logger));
        context.assertFalse(QueueRetryUtil.retryQueueItem(headers("x-queue-retry-504", "0"), 504, logger));
    }

    @Test
    public void testCaseSensitivity(TestContext context){
        context.assertTrue(QueueRetryUtil.retryQueueItem(headers("X-queue-retry-400", "0"), 503, logger));
        context.assertFalse(QueueRetryUtil.retryQueueItem(headers("x-QUEUE-retry-400", "0"), 400, logger));
        context.assertFalse(QueueRetryUtil.retryQueueItem(headers("x-queue-retRY-4XX", "0"), 400, logger));
        context.assertFalse(QueueRetryUtil.retryQueueItem(headers("X-QUEUE-RETRY-5XX", "0"), 503, logger));
    }

    private MultiMap headers(String name, String value){
        return new CaseInsensitiveHeaders().set(name, value);
    }
}
