package org.swisspush.gateleen.player;

import org.junit.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.swisspush.gateleen.player.exchange.Exchange;
import org.swisspush.gateleen.player.exchange.IgnoreHeadersTransformer;
import org.swisspush.gateleen.player.log.ResourceRequestLog;

import java.net.URI;
import java.util.Iterator;

import static org.swisspush.gateleen.player.exchange.Exchange.assertSameExchange;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class CompareTest {

    @Test
    public void testSame() {
        compare("simple");
    }

    @Test(expected = AssertionError.class)
    public void testDifferentHeaders() {
        compare("different-headers");
    }

    @Test(expected = AssertionError.class)
    public void testDifferentJson() {
        compare("different-json");
    }

    @Test(expected = AssertionError.class)
    public void testDifferentStatusCode() {
        compare("different-status");
    }

    private void compare(String actual) {
        Iterator<Exchange> outputLog =
                new ResourceRequestLog("", "classpath:logs/simple.log").transform(IgnoreHeadersTransformer.ignoreCommonHeaders()).iterator();

        for(Exchange in : new ResourceRequestLog("", "classpath:logs/"+actual+".log").transform(IgnoreHeadersTransformer.ignoreCommonHeaders())) {
            assertSameExchange(in, outputLog.next());
        }
    }

    @Test(expected = AssertionError.class)
    public void expectedNull() throws Exception {
        assertSameExchange(null, new Exchange(new RequestEntity<>(HttpMethod.PUT, new URI("/")), null));
    }

    @Test(expected = AssertionError.class)
    public void actualNull() throws Exception {
        assertSameExchange(new Exchange(new RequestEntity<>(HttpMethod.PUT, new URI("/")), null), null);
    }
}
