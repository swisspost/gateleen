package org.swisspush.gateleen.player;

import org.json.JSONObject;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.swisspush.gateleen.player.exchange.Exchange;
import org.swisspush.gateleen.player.exchange.IgnoreHeadersTransformer;
import org.swisspush.gateleen.player.log.ResourceRequestLog;
import org.swisspush.gateleen.player.player.Client;
import org.swisspush.gateleen.player.player.Player;

import java.util.Iterator;

import static com.google.common.base.Predicates.containsPattern;
import static com.google.common.base.Predicates.or;
import static com.jayway.jsonpath.Criteria.where;
import static org.swisspush.gateleen.player.exchange.Exchange.*;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class PlayerTest {

    public static final String URL = "http://localhost:7012";
    private Client client = new Client() {
        @Override
        public ResponseEntity<JSONObject> exchange(RequestEntity<JSONObject> request) {
            System.out.println(request);
            return new ResponseEntity<>(HttpStatus.OK);
        }
    };

    @Test
    public void testSimple() {
        new Player().
                setInputLog(URL, "classpath:logs/simple.log",
                        request(
                                or(
                                        url(containsPattern("/gateleen/galaxy/v1/wormhole/there$")),
                                        body(where("sound").exists(true))))::apply).
                setClient(client).
                play();
    }

    @Test
    @Ignore // assumes a running server on localhost
    public void testCompare() {
        new Player().
                setInputLog(URL, "classpath:logs/simple.log").
                setClient(client).
                play();

        Iterator<Exchange> outputLog = new ResourceRequestLog(URL, URL + "/gateleen/server/logs/gateleen/gateleen-requests.log").transform(IgnoreHeadersTransformer.ignoreCommonHeaders()).iterator();

        for(Exchange in : new ResourceRequestLog(URL, "classpath:logs/simple.log").transform(IgnoreHeadersTransformer.ignoreCommonHeaders())) {
            assertSameExchange(in, outputLog.next());
        }
    }
}
