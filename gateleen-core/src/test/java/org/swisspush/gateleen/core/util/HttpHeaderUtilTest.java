package org.swisspush.gateleen.core.util;


import io.vertx.core.MultiMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

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

}
