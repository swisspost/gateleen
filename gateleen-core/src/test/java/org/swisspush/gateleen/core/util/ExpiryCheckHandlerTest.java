package org.swisspush.gateleen.core.util;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.MultiMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.joda.time.DateTime;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(VertxUnitRunner.class)
public class ExpiryCheckHandlerTest {

    private static final String HTTP_HEADER_X_EXPIRE_AFTER = "X-Expire-After";
    private static final String HTTP_HEADER_X_QUEUE_EXPIRE_AFTER = "X-Queue-Expire-After";


    ///////////////////////////////////////////////////////////////////////////////
    // Below you'll find tests for method:
    // Integer getExpireAfter(MultiMap);
    ///////////////////////////////////////////////////////////////////////////////

    @Test
    public void getExpireAfter_returnsValueSpecifiedInHeader(TestContext testContext) {
        // Mock
        final String headerValue = "123456789";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedExpireValue = ExpiryCheckHandler.getExpireAfter(headers);

        // Assert
        testContext.assertEquals(headerValue, "" + returnedExpireValue);
    }

    @Test
    public void getExpireAfter_returnsZeroIfHeaderSpecifiesZero(TestContext testContext) {
        // Mock
        final String headerValue = "0";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedExpireValue = ExpiryCheckHandler.getExpireAfter(headers);

        // Assert
        testContext.assertEquals(headerValue, "" + returnedExpireValue);
    }

    @Test
    public void getExpireAfter_returnsNullIfHeaderNotPresent(TestContext testContext) {
        // Mock
        final String headerValue = null;
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedExpireValue = ExpiryCheckHandler.getExpireAfter(headers);

        // Assert
        testContext.assertNull(returnedExpireValue);
    }

    @Test
    public void getExpireAfter_returnsNullWhenHeaderIsMinusOne(TestContext testContext) {
        // Mock
        final String headerValue = "-1";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedExpireValue = ExpiryCheckHandler.getExpireAfter(headers);

        // Assert
        testContext.assertNull(returnedExpireValue);
    }

    @Test
    public void getExpireAfter_returnsNullIfHeaderValueBelowMinusOne(TestContext testContext) {
        // Mock
        final String headerValue = "-2147483640";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedValue = ExpiryCheckHandler.getExpireAfter(headers);

        // Assert
        testContext.assertNull(returnedValue);
    }

    @Test
    public void getExpireAfter_returnsNullIfHeaderValueIsNaN(TestContext testContext) {
        // Mock
        final String headerValue = "Definitively not a number :)";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedValue = ExpiryCheckHandler.getExpireAfter(headers);

        // Assert
        testContext.assertNull(returnedValue);
    }


    ///////////////////////////////////////////////////////////////////////////////
    // Below you'll find tests for method:
    // Integer getQueueExpireAfter(MultiMap);
    ///////////////////////////////////////////////////////////////////////////////

    @Test
    public void getQueueExpireAfter_returnsValueSpecifiedInHeader(TestContext testContext) {
        // Mock
        final String headerValue = "123456789";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_QUEUE_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedExpireValue = ExpiryCheckHandler.getQueueExpireAfter(headers);

        // Assert
        testContext.assertEquals(headerValue, "" + returnedExpireValue);
    }

    @Test
    public void getQueueExpireAfter_returnsZeroIfHeaderSpecifiesZero(TestContext testContext) {
        // Mock
        final String headerValue = "0";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_QUEUE_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedExpireValue = ExpiryCheckHandler.getQueueExpireAfter(headers);

        // Assert
        testContext.assertEquals(headerValue, "" + returnedExpireValue);
    }

    @Test
    public void getQueueExpireAfter_returnsNullIfHeaderNotPresent(TestContext testContext) {
        // Mock
        final String headerValue = null;
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_QUEUE_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedExpireValue = ExpiryCheckHandler.getQueueExpireAfter(headers);

        // Assert
        testContext.assertNull(returnedExpireValue);
    }

    @Test
    public void getQueueExpireAfter_returnsNullWhenHeaderIsMinusOne(TestContext testContext) {
        // Mock
        final String headerValue = "-1";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_QUEUE_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedExpireValue = ExpiryCheckHandler.getQueueExpireAfter(headers);

        // Assert
        testContext.assertNull(returnedExpireValue);
    }

    @Test
    public void getQueueExpireAfter_returnsNullIfHeaderValueIsNegative(TestContext testContext) {
        // Mock
        final String headerValue = "-1";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_QUEUE_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedValue = ExpiryCheckHandler.getQueueExpireAfter(headers);

        // Assert
        testContext.assertNull(returnedValue);
    }

    @Test
    public void getQueueExpireAfter_returnsNullIfHeaderValueIsNaN(TestContext testContext) {
        // Mock
        final String headerValue = "Definitively not a number :)";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_QUEUE_EXPIRE_AFTER, headerValue);

        // Trigger work
        final Integer returnedValue = ExpiryCheckHandler.getQueueExpireAfter(headers);

        // Assert
        testContext.assertNull(returnedValue);
    }


    ///////////////////////////////////////////////////////////////////////////////
    // Below you'll find tests for method:
    // Integer getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite(MultiMap, String);
    ///////////////////////////////////////////////////////////////////////////////

    @Test
    public void getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite_returnsValueSpecifiedInHeader(TestContext testContext) throws Exception {
        // Mock
        final String headerValue = "2147483640";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final int returnedExpireValue = ExpiryCheckHandler.getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite(headers).get();

        // Assert
        testContext.assertEquals(headerValue, "" + returnedExpireValue);
    }

    @Test
    public void getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite_returnsZeroIfHeaderSpecifiesZero(TestContext testContext) {
        // Mock
        final String headerValue = "0";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final int returnedExpireValue = ExpiryCheckHandler.getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite(headers).get();

        // Assert
        testContext.assertEquals(headerValue, "" + returnedExpireValue);
    }

    @Test
    public void getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite_returnsNullIfHeaderNotPresent(TestContext testContext) throws Exception {
        // Mock
        final String headerValue = null;
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final boolean didReturnSomething = ExpiryCheckHandler.getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite(headers).isPresent();

        // Assert
        testContext.assertFalse(didReturnSomething);
    }

    @Test
    public void getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite_returnsMinusOneWhenHeaderMinusOne(TestContext testContext) throws Exception {
        // Mock
        final String headerValue = "-1";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work
        final int returnedExpireValue = ExpiryCheckHandler.getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite(headers).get();

        // Assert
        testContext.assertEquals(headerValue, "" + returnedExpireValue);
    }

    @Test
    public void getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite_concealsNumberFormatExceptionIfHeaderValueBelowMinusOne(TestContext testContext) {
        // Mock
        final String headerValue = "-2143483640";
        final MultiMap headers = createHeaderReturningSimpleValue(HTTP_HEADER_X_EXPIRE_AFTER, headerValue);

        // Trigger work and expect it hides NumberFormatException that we as caller
        // never will have an idea what really happened.
        ExpiryCheckHandler.getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite(headers);
    }

    @Test
    public void parseDateTimeTest() {
        DateTime parsed = ExpiryCheckHandler.parseDateTime("2020-03-05T10:21:27.021+01:00");
        Assert.assertEquals(1583400087021L, parsed.getMillis());

        // CET is assumed if timezone is missing
        DateTime parsedWithoutTimezone = ExpiryCheckHandler.parseDateTime("2020-03-05T10:21:27.021");
        Assert.assertEquals(1583400087021L, parsedWithoutTimezone.getMillis());
    }

    ///////////////////////////////////////////////////////////////////////////////
    // Below some helper methods like factories etc. to share code in tests.
    ///////////////////////////////////////////////////////////////////////////////

    /**
     * @return A minimal, fast failing {@link MultiMap} returning specified value
     * in case specified key is requested.
     */
    private MultiMap createHeaderReturningSimpleValue(String key, String value) {
        if (key == null) throw new IllegalArgumentException("Cannot use null as key.");
        return new FastFailMultiMap() {
            @Override public @Nullable String get(String name) {
                if (key.equalsIgnoreCase(name)) {
                    return value;
                } else {
                    throw new UnsupportedOperationException("Unknown key '" + key + "' got requested. Use another mock if you've to provide more than one key-value pair.");
                }
            }
        };
    }

}
