package org.swisspush.gateleen.queue.queuing.splitter.executors;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.junit.Test;
import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitterConfiguration;

import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class QueueSplitExecutorFromRequestTest {

    @Test
    public void testMatchesWithStaticQueueName() {

        // Given
        QueueSplitExecutorFromRequest executor = new QueueSplitExecutorFromRequest(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                null,
                "x-rp-deviceid",
                null
        ));

        // Then
        assertTrue(executor.matches("queue-1"));
        assertFalse(executor.matches("queue-2"));
    }

    @Test
    public void testMatchesWithWildCharQueueName() {

        // Given
        QueueSplitExecutorFromRequest executor = new QueueSplitExecutorFromRequest(new QueueSplitterConfiguration(
                Pattern.compile("queue-[0-9]+"),
                "-",
                null,
                "x-rp-deviceid",
                null
        ));

        // Then
        assertTrue(executor.matches("queue-1"));
        assertTrue(executor.matches("queue-2"));
        assertTrue(executor.matches("queue-30"));
        assertFalse(executor.matches("queue-a"));
    }

    @Test
    public void testExecuteSplitWithHeader() {

        // Given
        QueueSplitExecutorFromRequest executor = new QueueSplitExecutorFromRequest(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                null,
                "x-rp-deviceid",
                null
        ));
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(new HeadersMultiMap().add("x-rp-deviceid", "A1B2C3D4E5F6"));

        // Then
        assertEquals("queue-1-A1B2C3D4E5F6", executor.executeSplit("queue-1", request));
    }

    @Test
    public void testExecuteSplitWithHeaderInDifferentCase() {

        // Given
        QueueSplitExecutorFromRequest executor = new QueueSplitExecutorFromRequest(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                null,
                "x-rp-deviceid",
                null
        ));
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(new HeadersMultiMap().add("X-RP-DEVICEID", "A1B2C3D4E5F6"));

        // Then
        assertEquals("queue-1-A1B2C3D4E5F6", executor.executeSplit("queue-1", request));
    }

    @Test
    public void testExecuteSplitWithHeaderButMissingInRequest() {

        // Given
        QueueSplitExecutorFromRequest executor = new QueueSplitExecutorFromRequest(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                null,
                "x-rp-deviceid",
                null
        ));
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(new HeadersMultiMap());

        // Then
        assertEquals("queue-1", executor.executeSplit("queue-1", request));
    }

    @Test
    public void testExecuteSplitWithUrl() {

        // Given
        QueueSplitExecutorFromRequest executor = new QueueSplitExecutorFromRequest(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                null,
                null,
                Pattern.compile("/path1/(.+)/path3/(.+)")
        ));
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(request.uri()).thenReturn("/path1/path2/path3/path4");

        // Then
        assertEquals("queue-1-path2-path4", executor.executeSplit("queue-1", request));
    }
    @Test
    public void testExecuteSplitWithUrlAndHeader() {

        // Given
        QueueSplitExecutorFromRequest executor = new QueueSplitExecutorFromRequest(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                null,
                "x-rp-deviceid",
                Pattern.compile("/path1/(.+)/path3/(.+)")
        ));
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(new HeadersMultiMap().add("x-rp-deviceid", "A1B2C3D4E5F6"));
        when(request.uri()).thenReturn("/path1/path2/path3/path4");

        // Then
        assertEquals("queue-1-path2-path4-A1B2C3D4E5F6", executor.executeSplit("queue-1", request));
    }

    @Test
    public void testExecuteSplitForWrongQueue() {

        // Given
        QueueSplitExecutorFromRequest executor = new QueueSplitExecutorFromRequest(new QueueSplitterConfiguration(
                Pattern.compile("queue-1"),
                "-",
                null,
                "x-rp-deviceid",
                null
        ));
        HttpServerRequest request = mock(HttpServerRequest.class);

        // Then
        assertEquals("queue-2", executor.executeSplit("queue-2", request));
    }
}
