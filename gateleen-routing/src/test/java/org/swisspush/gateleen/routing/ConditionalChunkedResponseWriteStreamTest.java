package org.swisspush.gateleen.routing;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConditionalChunkedResponseWriteStreamTest {

    @Test
    public void enablesChunkedTransferOnlyForNonEmptyPayload() {
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(response.write(any(Buffer.class))).thenReturn(Future.succeededFuture());
        ConditionalChunkedResponseWriteStream stream = new ConditionalChunkedResponseWriteStream(response, true);

        stream.write(Buffer.buffer());

        verify(response, never()).setChunked(true);

        stream.write(Buffer.buffer("response body"));

        verify(response).setChunked(true);
    }

    @Test
    public void doesNotEnableChunkedTransferForNonChunkedResponse() {
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(response.write(any(Buffer.class))).thenReturn(Future.succeededFuture());
        ConditionalChunkedResponseWriteStream stream = new ConditionalChunkedResponseWriteStream(response, false);

        stream.write(Buffer.buffer("response body"));

        verify(response, never()).setChunked(true);
    }
}
