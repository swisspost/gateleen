package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

@RunWith(VertxUnitRunner.class)
public class NoOpQueueSplitterTest {

    @Test
    public void splitKeepSameQueue(TestContext context) {

        // Given
        NoOpQueueSplitter splitter = new NoOpQueueSplitter();
        HttpServerRequest request = mock(HttpServerRequest.class);

        // When
        splitter.initialize().onComplete(event -> {

            // Then
            context.assertEquals("my-queue-01", splitter.convertToSubQueue("my-queue-01", request));
            context.assertEquals("my-queue-02", splitter.convertToSubQueue("my-queue-02", request));
            context.assertEquals("my-queue-03", splitter.convertToSubQueue("my-queue-03", request));
        });
    }
}
