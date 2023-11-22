package org.swisspush.gateleen.logging;

import io.vertx.core.Vertx;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.awaitility.Duration.TWO_SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.swisspush.gateleen.logging.LoggingResourceManager.UPDATE_ADDRESS;

/**
 * Tests for the {@link DefaultLogAppenderRepository} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class DefaultLogAppenderRepositoryTest {

    private Vertx vertx;
    private DefaultLogAppenderRepository repository;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        repository = new DefaultLogAppenderRepository(vertx);
    }

    @Test
    public void testAppenderNotInRepository(TestContext context) {
        context.assertFalse(repository.hasAppender("my-appender"));
        context.assertNull(repository.getAppender("my-appender"));
    }

    @Test
    public void testAddAppender(TestContext context) {
        context.assertFalse(repository.hasAppender("my-appender"));

        Appender appender = new ConsoleAppender.Builder<>().setName("my-appender").build();
        repository.addAppender("my-appender", appender);

        context.assertTrue(repository.hasAppender("my-appender"));
        context.assertNotNull(repository.getAppender("my-appender"));
    }

    @Test
    public void testClearRepository(TestContext context) {
        context.assertFalse(repository.hasAppender("my-appender"));
        Appender appender = new ConsoleAppender.Builder<>().setName("my-appender").build();
        repository.addAppender("my-appender", appender);
        context.assertTrue(repository.hasAppender("my-appender"));
        repository.clearRepository();
        context.assertFalse(repository.hasAppender("my-appender"));
    }

    @Test
    public void testClearRepositoryTriggeredByEventbus(TestContext context) {
        context.assertFalse(repository.hasAppender("my-appender"));
        Appender appender = new ConsoleAppender.Builder<>().setName("my-appender").build();
        repository.addAppender("my-appender", appender);
        context.assertTrue(repository.hasAppender("my-appender"));

        vertx.eventBus().publish(UPDATE_ADDRESS, true);

        await().atMost(TWO_SECONDS).until(() -> repository.hasAppender("my-appender"), equalTo(false));
    }
}
