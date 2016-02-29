package org.swisspush.gateleen.player;

import com.jayway.jsonpath.JsonPath;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.http.HttpStatus;

import static com.google.common.base.Predicates.*;
import static com.jayway.jsonpath.Criteria.where;
import static org.swisspush.gateleen.player.exchange.Exchange.*;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
@Ignore
public class PredicatesTest {
    @Test
    public void testPredicates() {
        request(
                and(
                        url(containsPattern("/gateleen/galaxy.*")),
                        header("x-service", or(equalTo("vogon"), equalTo("colin")))));
        and(
                request(
                        and(url(containsPattern("/gateleen/galaxy.*")))
                ),
                response(
                        status(equalTo(HttpStatus.OK))
                )
        );
    }

    @Test
    public void testJson() {
        JsonPath path = JsonPath.compile("$[?]", where("hello").is("world"));
        Object read = path.read("{ \"hello\": \"world\", \"hello2\": \"world2\" }");
        System.out.println(read);
    }
}