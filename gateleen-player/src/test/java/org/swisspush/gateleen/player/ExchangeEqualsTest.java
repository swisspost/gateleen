package org.swisspush.gateleen.player;

import com.google.common.collect.Sets;
import org.json.JSONObject;
import org.junit.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.swisspush.gateleen.player.exchange.Exchange;
import org.swisspush.gateleen.player.exchange.IgnoreHeadersTransformer;
import org.swisspush.gateleen.player.log.ResourceRequestLog;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;

/**
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class ExchangeEqualsTest {

    @Test
    public void testSame() {
        assertEquals(getExpectedExchangeSet(), getActualExchangeSet("simple"));
    }

    @Test(expected = AssertionError.class)
    public void testDifferentHeaders() {
        assertEquals(getExpectedExchangeSet(), getActualExchangeSet("different-headers"));
    }

    @Test(expected = AssertionError.class)
    public void testDifferentJson() {
        // assertEquals(getExpectedExchangeSet(), getActualExchangeSet("different-json"));

        Set<Exchange> expected = getExpectedExchangeSet();
        Set<Exchange> actual = getActualExchangeSet("different-json");
        expected.removeAll(actual);

        assertEquals("Missing exchange(s)", new HashSet<Exchange>(), expected);
    }

    @Test(expected = AssertionError.class)
    public void testDifferentStatusCode() {
        assertEquals(getExpectedExchangeSet(), getActualExchangeSet("different-status"));
    }

    private Set<Exchange> getExpectedExchangeSet() {
        return Sets.newHashSet(new ResourceRequestLog("", "classpath:logs/simple.log").transform(IgnoreHeadersTransformer.ignoreCommonHeaders()));
    }

    private Set<Exchange> getActualExchangeSet(String actual) {
        return Sets.newHashSet(new ResourceRequestLog("", "classpath:logs/" + actual + ".log").transform(IgnoreHeadersTransformer.ignoreCommonHeaders()));

    }

    @Test
    public void actualNull() throws Exception {
        assertFalse(new Exchange(new RequestEntity<>(HttpMethod.PUT, new URI("/")), null).equals(null));
    }
}
