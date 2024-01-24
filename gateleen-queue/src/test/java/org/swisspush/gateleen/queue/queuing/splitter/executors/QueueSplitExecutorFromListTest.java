package org.swisspush.gateleen.queue.queuing.splitter.executors;

import io.vertx.core.http.HttpServerRequest;
import org.junit.Test;
import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitterConfiguration;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class QueueSplitExecutorFromListTest {

    @Test
    public void testMatchesWithStaticQueueName() {

        // Given
        QueueSplitExecutorFromList executor = new QueueSplitExecutorFromList(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                List.of("A", "B", "C", "D"),
                null,
                null
        ));

        // Then
        assertTrue(executor.matches("queue-1"));
        assertFalse(executor.matches("queue-2"));
    }

    @Test
    public void testMatchesWithWildCharQueueName() {

        // Given
        QueueSplitExecutorFromList executor = new QueueSplitExecutorFromList(new QueueSplitterConfiguration(
                Pattern.compile("queue-[0-9]+"),
                "-",
                List.of("A", "B", "C", "D"),
                null,
                null
        ));

        // Then
        assertTrue(executor.matches("queue-1"));
        assertTrue(executor.matches("queue-2"));
        assertTrue(executor.matches("queue-30"));
        assertFalse(executor.matches("queue-a"));
    }

    @Test
    public void testExecuteSplit() {

        // Given
        QueueSplitExecutorFromList executor = new QueueSplitExecutorFromList(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                List.of("A", "B", "C", "D"),
                null,
                null
        ));
        HttpServerRequest request = mock(HttpServerRequest.class);

        // When

        // Then
        assertEquals("queue-1-A", executor.executeSplit("queue-1", request));
        assertEquals("queue-1-B", executor.executeSplit("queue-1", request));
        assertEquals("queue-1-C", executor.executeSplit("queue-1", request));
        assertEquals("queue-1-D", executor.executeSplit("queue-1", request));
        assertEquals("queue-1-A", executor.executeSplit("queue-1", request));
        assertEquals("queue-1-B", executor.executeSplit("queue-1", request));
        assertEquals("queue-1-C", executor.executeSplit("queue-1", request));
    }

    @Test
    public void testExecuteSplitForWrongQueue() {

        // Given
        QueueSplitExecutorFromList executor = new QueueSplitExecutorFromList(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                List.of("A", "B", "C", "D"),
                null,
                null
        ));
        HttpServerRequest request = mock(HttpServerRequest.class);

        // Then
        assertEquals("queue-2", executor.executeSplit("queue-2", request));
    }
}
