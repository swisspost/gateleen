package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.http.HttpServerRequest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class NoOpQueueSplitterTest {

    @Test
    public void splitKeepSameQueue() {

        // Given
        NoOpQueueSplitter splitter = new NoOpQueueSplitter();
        HttpServerRequest request = mock(HttpServerRequest.class);

        // When
        splitter.initialize().onComplete( event -> {

        // Then
        assertEquals("my-queue-01", splitter.convertToSubQueue("my-queue-01", request));
        assertEquals("my-queue-02", splitter.convertToSubQueue("my-queue-02", request));
        assertEquals("my-queue-03", splitter.convertToSubQueue("my-queue-03", request));
        });
    }
}
