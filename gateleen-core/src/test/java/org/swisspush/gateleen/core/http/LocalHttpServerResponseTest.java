package org.swisspush.gateleen.core.http;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClosedException;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests how {@link LocalHttpServerResponse} reports a response closed before it was completed to
 * the local client.
 */
@RunWith(VertxUnitRunner.class)
public class LocalHttpServerResponseTest {

    private Vertx vertx;
    private LocalHttpServerResponse response;
    private final AtomicReference<HttpClientResponse> clientResponse = new AtomicReference<>();

    /** Creates a response whose client response handler records the delivered client response. */
    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        response = new LocalHttpServerResponse(vertx, GateleenExceptionFactory.newGateleenThriftyExceptionFactory());
        response.setHttpClientResponseHandler((AsyncResult<HttpClientResponse> result) -> clientResponse.set(result.result()));
    }

    /**
     * Closes Vert.x.
     *
     * @param ctx test context
     */
    @After
    public void tearDown(TestContext ctx) {
        vertx.close(ctx.asyncAssertSuccess());
    }

    /**
     * Closing a started but not ended response fails the client response with
     * {@link HttpClosedException} after the already written data, never ends it, and rejects
     * later writes and ends.
     */
    @Test
    public void testCloseOfStartedResponseFailsClientResponse(TestContext ctx) {
        Async failed = ctx.async();
        Buffer received = Buffer.buffer();
        AtomicBoolean ended = new AtomicBoolean();
        vertx.runOnContext(nothing -> {
            response.setStatusCode(200).setChunked(true);
            response.write(Buffer.buffer("part"));
            HttpClientResponse client = clientResponse.get();
            ctx.assertNotNull(client, "Writing must hand the response to the client");
            client.handler(received::appendBuffer);
            client.endHandler(v -> ended.set(true));
            client.exceptionHandler(ex -> {
                ctx.assertTrue(ex instanceof HttpClosedException, String.valueOf(ex));
                ctx.assertEquals("part", received.toString());
                failed.complete();
            });

            response.close();

            ctx.assertTrue(response.closed());
            ctx.assertTrue(response.write(Buffer.buffer("more")).failed());
            ctx.assertTrue(response.end(Buffer.buffer("Bad Gateway")).failed());
            ctx.assertTrue(response.end().failed());
            ctx.assertFalse(response.ended());
        });
        failed.awaitSuccess(5000);
        // Give a wrongly delivered end event the chance to arrive.
        Async settled = ctx.async();
        vertx.setTimer(100, id -> settled.complete());
        settled.awaitSuccess(5000);
        ctx.assertFalse(ended.get(), "An aborted response must not be ended");
        ctx.assertEquals("part", received.toString());
    }

    /**
     * Closes a started response before the client registered any handler (as when the client
     * registers them on a later event-loop turn) and asserts that registering them afterwards still
     * reports the abort, after the written data and exactly once.
     */
    @Test
    public void testAbortReportedToHandlerRegisteredAfterClose(TestContext ctx) {
        Async failed = ctx.async();
        Buffer received = Buffer.buffer();
        AtomicInteger aborts = new AtomicInteger();
        vertx.runOnContext(nothing -> {
            response.setStatusCode(200).setChunked(true);
            response.write(Buffer.buffer("part"));
            response.close();
            vertx.setTimer(50, id -> {
                HttpClientResponse client = clientResponse.get();
                client.handler(received::appendBuffer);
                client.endHandler(v -> ctx.fail("An aborted response must not be ended"));
                client.exceptionHandler(ex -> {
                    ctx.assertTrue(ex instanceof HttpClosedException, String.valueOf(ex));
                    ctx.assertEquals("part", received.toString());
                    aborts.incrementAndGet();
                    failed.complete();
                });
            });
        });
        failed.awaitSuccess(5000);
        awaitSettled(ctx);
        ctx.assertEquals(1, aborts.get());
    }

    /**
     * Reports the abort to an installed handler and asserts that neither closing again nor
     * registering another exception handler afterwards reports it a second time.
     */
    @Test
    public void testAbortReportedExactlyOnce(TestContext ctx) {
        Async failed = ctx.async();
        AtomicInteger aborts = new AtomicInteger();
        vertx.runOnContext(nothing -> {
            response.setStatusCode(200).setChunked(true);
            response.write(Buffer.buffer("part"));
            HttpClientResponse client = clientResponse.get();
            client.handler(chunk -> { });
            client.exceptionHandler(ex -> {
                aborts.incrementAndGet();
                failed.complete();
            });
            response.close();
            response.close();
        });
        failed.awaitSuccess(5000);
        Async reregistered = ctx.async();
        vertx.runOnContext(nothing -> {
            clientResponse.get().exceptionHandler(ex -> aborts.incrementAndGet());
            reregistered.complete();
        });
        reregistered.awaitSuccess(5000);
        awaitSettled(ctx);
        ctx.assertEquals(1, aborts.get());
    }

    /**
     * Queues several chunks before the client registers its data handler, closes the response
     * and asserts that the abort is reported only after all queued chunks were delivered, whether
     * the exception handler is registered before or after the data handler.
     */
    @Test
    public void testAbortReportedAfterAllQueuedChunks(TestContext ctx) {
        for (boolean exceptionHandlerFirst : new boolean[]{true, false}) {
            LocalHttpServerResponse queued = new LocalHttpServerResponse(vertx,
                    GateleenExceptionFactory.newGateleenThriftyExceptionFactory());
            AtomicReference<HttpClientResponse> client = new AtomicReference<>();
            queued.setHttpClientResponseHandler(result -> client.set(result.result()));
            Async failed = ctx.async();
            Buffer received = Buffer.buffer();
            vertx.runOnContext(nothing -> {
                queued.setStatusCode(200).setChunked(true);
                for (int i = 0; i < 5; i++) {
                    queued.write(Buffer.buffer("c" + i));
                }
                Handler<Throwable> onAbort = ex -> {
                    ctx.assertEquals("c0c1c2c3c4", received.toString(), "first=" + exceptionHandlerFirst);
                    failed.complete();
                };
                if (exceptionHandlerFirst) {
                    client.get().exceptionHandler(onAbort);
                    client.get().handler(received::appendBuffer);
                } else {
                    client.get().handler(received::appendBuffer);
                    client.get().exceptionHandler(onAbort);
                }
                queued.close();
            });
            failed.awaitSuccess(5000);
        }
    }

    /**
     * A data handler that throws stops the pump; the abort must still be reported instead of
     * waiting forever for the remaining queued chunks.
     */
    @Test
    public void testAbortReportedWhenPumpStops(TestContext ctx) {
        Async failed = ctx.async();
        vertx.runOnContext(nothing -> {
            response.setStatusCode(200).setChunked(true);
            response.write(Buffer.buffer("c0"));
            response.write(Buffer.buffer("c1"));
            HttpClientResponse client = clientResponse.get();
            client.handler(chunk -> {
                throw new IllegalStateException("data handler failed");
            });
            client.exceptionHandler(ex -> {
                if (ex instanceof HttpClosedException) {
                    failed.complete();
                }
            });
            response.close();
        });
        failed.awaitSuccess(5000);
    }

    /**
     * Waits briefly so that a wrongly repeated or late event has the chance to arrive.
     *
     * @param ctx test context
     */
    private void awaitSettled(TestContext ctx) {
        Async settled = ctx.async();
        vertx.setTimer(100, id -> settled.complete());
        settled.awaitSuccess(5000);
    }

    /** Closing an already ended response only marks it as closed; the client sees a normal end. */
    @Test
    public void testCloseOfEndedResponseKeepsItComplete(TestContext ctx) {
        Async done = ctx.async();
        vertx.runOnContext(nothing -> {
            response.setStatusCode(200);
            response.end(Buffer.buffer("complete"));
            HttpClientResponse client = clientResponse.get();
            client.exceptionHandler(ex -> ctx.fail("No exception expected: " + ex));
            response.close();
            ctx.assertTrue(response.closed());
            ctx.assertTrue(response.ended());
            Buffer body = Buffer.buffer();
            client.handler(body::appendBuffer);
            client.endHandler(v -> {
                ctx.assertEquals("complete", body.toString());
                vertx.setTimer(100, id -> done.complete());
            });
        });
        done.awaitSuccess(5000);
    }

    /**
     * Closing a response that was not handed to the client yet only marks it as closed and still
     * allows an error response to be written (unchanged behaviour).
     */
    @Test
    public void testCloseBeforeStartOnlyMarksClosed(TestContext ctx) {
        Async done = ctx.async();
        vertx.runOnContext(nothing -> {
            response.close();
            ctx.assertTrue(response.closed());
            ctx.assertNull(clientResponse.get());
            response.setStatusCode(502);
            ctx.assertTrue(response.end(Buffer.buffer("Bad Gateway")).succeeded());
            ctx.assertEquals(502, clientResponse.get().statusCode());
            done.complete();
        });
        done.awaitSuccess(5000);
    }
}
