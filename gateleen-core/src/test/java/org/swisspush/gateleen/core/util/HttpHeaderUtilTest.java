package org.swisspush.gateleen.core.util;


import io.vertx.core.MultiMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;


@RunWith(VertxUnitRunner.class)
public class HttpHeaderUtilTest {

    private static String CONNECTION = HttpRequestHeader.CONNECTION.getName();

    @Test
    public void removeNonForwardHeadersTest(TestContext testContext) {

        // Mock an example header
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(CONNECTION, "one" );
        headers.add(CONNECTION, "two" );
        headers.add(CONNECTION, "three" );
        headers.add("an-unrelated-one", "123");
        headers.add("another-unrelated", "close");
        headers.add("one", "stuff");
        headers.add("three", "other stuff");

        // Apply filter
        headers = HttpHeaderUtil.removeNonForwardHeaders(headers);

        // Assert unrelated still exists
        testContext.assertTrue(headers.contains("an-unrelated-one"));
        testContext.assertTrue(headers.contains("another-unrelated"));

        // Assert non forwards are removed.
        testContext.assertFalse(headers.contains(CONNECTION));
        testContext.assertFalse(headers.contains("one"));
        testContext.assertFalse(headers.contains("three"));

        // Assert there are exactly the two valid remaining.
        testContext.assertEquals(2, headers.size());
    }

    @Test
    public void getHeaderValueTest(TestContext testContext) {

        // Mock an example header
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("dummy-header", "123");
        headers.add("even-more-dummy-header", "anyvalue");
        headers.add("host", "host:1234");

        String value = HttpHeaderUtil.getHeaderValue(headers, "Host");
        // Assert correct returned value found even with unequal case
        testContext.assertEquals(value,"host:1234");

        value = HttpHeaderUtil.getHeaderValue(headers, "someHeader");
        // Assert not found
        testContext.assertNull(value);

    }

    @Test
    public void hasMatchingHeaderTest(TestContext testContext) {

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("dummy-header", "123");
        headers.add("even-more-dummy-header", "anyvalue");
        headers.add("host", "host:1234");

        testContext.assertFalse(HttpHeaderUtil.hasMatchingHeader(headers, Pattern.compile("x-foo")));

        testContext.assertFalse(HttpHeaderUtil.hasMatchingHeader(headers, Pattern.compile("dummy-header")));
        testContext.assertFalse(HttpHeaderUtil.hasMatchingHeader(headers, Pattern.compile("dummy-header: 124")));
        testContext.assertFalse(HttpHeaderUtil.hasMatchingHeader(headers, Pattern.compile("dummy-header: [0-9]{2}")));

        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(headers, Pattern.compile("dummy-header.*")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(headers, Pattern.compile("dummy-header: 123")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(headers, Pattern.compile("dummy-header: (123|999)")));
    }

    @Test
    public void mergeHeadersNoConflict(TestContext testContext) {
        MultiMap destination = MultiMap.caseInsensitiveMultiMap();
        destination.add(HttpRequestHeader.CONTENT_LENGTH.getName(), "123");
        destination.add("host", "host:1234");

        MultiMap source1 = MultiMap.caseInsensitiveMultiMap();
        source1.add(HttpRequestHeader.CONTENT_LENGTH.getName(), "123");
        source1.add("dummy-header", "123");

        HttpHeaderUtil.mergeHeaders(destination, source1, "test");
        testContext.assertEquals(3, destination.size());
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("Content-Length: 123")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("host: host:1234")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("dummy-header: 123")));
    }

    @Test
    public void mergeHeadersWithConflictingContentLength(TestContext testContext) {
        MultiMap destination = MultiMap.caseInsensitiveMultiMap();
        destination.add(HttpRequestHeader.CONTENT_LENGTH.getName(), "123");
        destination.add("host", "host:1234");

        MultiMap source2 = MultiMap.caseInsensitiveMultiMap();
        // here we add a conflicting value
        source2.add(HttpRequestHeader.CONTENT_LENGTH.getName(), "126");
        source2.add("dummy-header", "123");
        HttpHeaderUtil.mergeHeaders(destination, source2, "test");
        testContext.assertEquals(3, destination.size());

        // the implementation takes the value passed in through parameter "source"
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("Content-Length: 126")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("host: host:1234")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("dummy-header: 123")));

    }

    @Test
    public void mergeHeadersWithConflictingBlah(TestContext testContext) {
        MultiMap destination = MultiMap.caseInsensitiveMultiMap();
        destination.add("test-1", "1");
        destination.add("test-2", "");
        destination.add("test-3", "");
        destination.add("test-4", "4");

        MultiMap source3 = MultiMap.caseInsensitiveMultiMap();
        source3.add("test-1", "");
        source3.add("test-2", "2");
        source3.add("test-3", "");
        source3.add("test-4", "4");
        source3.add("host", "host:1234");

        HttpHeaderUtil.mergeHeaders(destination, source3, "test");

        testContext.assertEquals(5, destination.size());
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("test-1: ")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("test-2: 2")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("test-3: ")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("test-4: 4")));
        testContext.assertTrue(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("host: host:1234")));
        testContext.assertFalse(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("test-1: 1")));
        testContext.assertFalse(HttpHeaderUtil.hasMatchingHeader(destination, Pattern.compile("test-2: ")));
    }
}
