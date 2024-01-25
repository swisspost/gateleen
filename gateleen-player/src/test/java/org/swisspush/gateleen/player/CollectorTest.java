package org.swisspush.gateleen.player;

import com.google.common.collect.FluentIterable;
import org.junit.Ignore;
import org.junit.Test;
import org.swisspush.gateleen.player.exchange.Exchange;
import org.swisspush.gateleen.player.exchange.IgnoreHeadersTransformer;
import org.swisspush.gateleen.player.log.Collector;
import org.swisspush.gateleen.player.log.EventBusCollector;
import org.swisspush.gateleen.player.log.ResourceRequestLog;
import org.swisspush.gateleen.player.player.ExchangeHandler;
import org.swisspush.gateleen.player.player.Player;

import java.util.Iterator;

import static com.google.common.base.Predicates.equalTo;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.swisspush.gateleen.player.exchange.Exchange.*;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
@Ignore // assumes a running server on localhost
public class CollectorTest {

    public static final String URL = "http://localhost:7012";

    @Test
    public void testEventBusCollector() throws Exception {
        Collector collector = new EventBusCollector(URL, "/gateleen/server/event/v1/sock", "event/gateleen-request-log", exchange -> true);
        new Player().
                setInputLog(URL, "classpath:logs/simple.log", exchange -> true).
                setOutputCollector(collector).
                setExchangeHandler(new ExchangeHandler(){
                    @Override
                    public boolean after(final Exchange exchange, final FluentIterable<Exchange> tailOutputLog) {
                        await().atMost(FIVE_SECONDS).until(() -> !tailOutputLog.filter(request(header("x-request-id",
                                equalTo(exchange.getRequest().getHeaders().get("x-request-id").get(0))))).isEmpty());
                        return true;
                    }
                }).
                play();
    }

    @Test
    public void testFullOutputLog() throws Exception {
        Iterator<Exchange> outputLog = new Player().
                setInputLog(URL, "classpath:logs/simple.log").
                setOutputCollector(URL, "/gateleen/server/event/v1/sock", "event/gateleen-request-log").
                play().getOutputLog().transform(IgnoreHeadersTransformer.ignoreCommonHeaders()).iterator();

        for(Exchange in : new ResourceRequestLog(URL, "classpath:logs/simple.log").transform(IgnoreHeadersTransformer.ignoreCommonHeaders())) {
            assertSameExchange(in, outputLog.next());
        }
    }
}
