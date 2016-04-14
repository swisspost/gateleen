package org.swisspush.gateleen.player;

import org.junit.Test;
import org.swisspush.gateleen.player.exchange.Exchange;
import org.swisspush.gateleen.player.exchange.ReplaceStringTransformer;
import org.swisspush.gateleen.player.log.ResourceRequestLog;

import static junit.framework.TestCase.assertTrue;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class ReplaceStringTransformerTest {
    @Test
    public void testVariableData() {

        for(Exchange exchange: new ResourceRequestLog("http://localhost:7012/gateleen", "classpath:logs/variable-data.log").
                transform(ReplaceStringTransformer.clearTimestamps()).transform(ReplaceStringTransformer.clearUUIDs())) {

            System.out.println(exchange);

            assertTrue(!exchange.getRequest().getHeaders().getFirst("x-request-id").equals("%l81J") ||
                    exchange.getRequest().getHeaders().getFirst("x-client-timestamp").equals("1970-01-01T00:00:00Z"));
            assertTrue(!exchange.getRequest().getHeaders().getFirst("x-request-id").equals("%l81J") ||
                    exchange.getResponse().getHeaders().getFirst("custom").equals("1970-01-01T00:00:00Z"));

            assertTrue(!exchange.getRequest().getHeaders().getFirst("x-request-id").equals("%h6Ap") ||
                    exchange.getRequest().getBody().toString().contains("1970-01-01T00:00:00Z"));

            assertTrue(!exchange.getRequest().getHeaders().getFirst("x-request-id").equals("%5yzL") ||
                    exchange.getRequest().getUrl().toString().contains("00000000-0000-0000-0000-000000000000"));
        }
    }

}
