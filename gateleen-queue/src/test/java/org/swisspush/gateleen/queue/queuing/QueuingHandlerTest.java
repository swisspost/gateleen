package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.MultiMap;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.joda.time.DateTime;
import org.junit.Test;
import org.swisspush.gateleen.core.util.ExpiryCheckHandler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.swisspush.gateleen.core.util.ExpiryCheckHandler.EXPIRE_AFTER_HEADER;
import static org.swisspush.gateleen.core.util.ExpiryCheckHandler.QUEUE_EXPIRE_AFTER_HEADER;
import static org.swisspush.gateleen.queue.queuing.QueuingHandler.CLIENT_TIMESTAMP_HEADER;

/**
 * Tests for the {@link QueuingHandler#isRequestExpired(MultiMap)} method.
 */
public class QueuingHandlerTest {

    @Test
    public void isRequestExpired_returnsFalseWhenHeadersAreNull() {
        assertFalse(QueuingHandler.isRequestExpired(null));
    }

    @Test
    public void isRequestExpired_returnsFalseWhenClientTimestampHeaderIsMissing() {
        MultiMap headers = new HeadersMultiMap();
        headers.set(EXPIRE_AFTER_HEADER, "1");

        assertFalse(QueuingHandler.isRequestExpired(headers));
    }

    @Test
    public void isRequestExpired_returnsFalseWhenClientTimestampIsNotParseable() {
        MultiMap headers = new HeadersMultiMap();
        headers.set(CLIENT_TIMESTAMP_HEADER, "not-a-valid-timestamp");
        headers.set(EXPIRE_AFTER_HEADER, "1");

        assertFalse(QueuingHandler.isRequestExpired(headers));
    }

    @Test
    public void isRequestExpired_returnsFalseWhenNoExpireHeadersArePresent() {
        MultiMap headers = new HeadersMultiMap();
        headers.set(CLIENT_TIMESTAMP_HEADER, ExpiryCheckHandler.printDateTime(DateTime.now().minusHours(1)));

        assertFalse(QueuingHandler.isRequestExpired(headers));
    }

    @Test
    public void isRequestExpired_returnsTrueWhenExpireAfterElapsedSinceClientTimestamp() {
        MultiMap headers = new HeadersMultiMap();
        headers.set(CLIENT_TIMESTAMP_HEADER, ExpiryCheckHandler.printDateTime(DateTime.now().minusSeconds(10)));
        headers.set(EXPIRE_AFTER_HEADER, "1");

        assertTrue(QueuingHandler.isRequestExpired(headers));
    }

    @Test
    public void isRequestExpired_returnsFalseWhenExpireAfterNotYetElapsedSinceClientTimestamp() {
        MultiMap headers = new HeadersMultiMap();
        headers.set(CLIENT_TIMESTAMP_HEADER, ExpiryCheckHandler.printDateTime(DateTime.now()));
        headers.set(EXPIRE_AFTER_HEADER, "3600");

        assertFalse(QueuingHandler.isRequestExpired(headers));
    }

    @Test
    public void isRequestExpired_queueExpireAfterOverridesExpireAfter() {
        MultiMap headers = new HeadersMultiMap();
        // X-Expire-After alone would NOT be expired yet, but x-queue-expire-after overrides it and IS expired
        headers.set(CLIENT_TIMESTAMP_HEADER, ExpiryCheckHandler.printDateTime(DateTime.now().minusSeconds(10)));
        headers.set(EXPIRE_AFTER_HEADER, "3600");
        headers.set(QUEUE_EXPIRE_AFTER_HEADER, "1");

        assertTrue(QueuingHandler.isRequestExpired(headers));
    }

    @Test
    public void isRequestExpired_returnsFalseWhenExpireAfterIsInfinite() {
        MultiMap headers = new HeadersMultiMap();
        headers.set(CLIENT_TIMESTAMP_HEADER, ExpiryCheckHandler.printDateTime(DateTime.now().minusHours(1)));
        headers.set(EXPIRE_AFTER_HEADER, "-1");

        assertFalse(QueuingHandler.isRequestExpired(headers));
    }
}
