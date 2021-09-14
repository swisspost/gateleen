package org.swisspush.gateleen.core.util;

import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(VertxUnitRunner.class)
public class HttpHeaderUtilTest {

    private static String CONNECTION = HttpRequestHeader.CONNECTION.getName();

    @Test
    public void removeNonForwardHeadersTest(TestContext testContext) {

        // Mock an example header
        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
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

}
