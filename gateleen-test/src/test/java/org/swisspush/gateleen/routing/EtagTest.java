package org.swisspush.gateleen.routing;

import org.swisspush.gateleen.AbstractTest;
import com.jayway.restassured.RestAssured;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.*;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class EtagTest extends AbstractTest {

    private final String ETAG_HEADER = "Etag";
    private final String IF_NONE_MATCH_HEADER = "if-none-match";
    private final String EXPIRE_AFTER_HEADER = "x-expire-after";
    private final String MAX_EXPIRE_IN_MILLIS = "9999999999999";

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath("/server/tests/etags");
        RestAssured.requestSpecification.baseUri("http://localhost:" + MAIN_PORT + ROOT);
    }

    @Test
    public void testEtag(TestContext context) {
        Async async = context.async();
        delete();

        with().body("{ \"foo\": \"bar\" }").put("resources/res1");

        String etag = get("resources/res1").getHeader(ETAG_HEADER);
        context.assertNotNull(etag, "Etag header should be available in response headers");
        context.assertTrue(etag.length() > 0, "Etag header should not be empty");

        // get requests with no if-none-match header should result in statuscode 200
        when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag))
                .statusCode(200);
        when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag))
                .statusCode(200);

        // not modified resource should result in statuscode 304
        given().header(IF_NONE_MATCH_HEADER, etag).when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag))
                .statusCode(304)
                .header("Transfer-Encoding", nullValue())
                .header("Content-length", equalTo("0"));

        // non matching etags should result in statuscode 200
        given().header(IF_NONE_MATCH_HEADER, "NonMatchingEtag").when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag))
                .statusCode(200);

        // update the resource
        with().body("{ \"foo\": \"bar2\" }").put("resources/res1");

        // etag should have changed
        when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, not(equalTo(etag)))
                .statusCode(200);

        async.complete();
    }

    @Test
    public void testEtagPUTWithoutHeader(TestContext context) {
        Async async = context.async();
        delete();

        String content = "{ \"foo\": \"bar\" }";
        with().body(content).put("resources/res1");
        String etag = get("resources/res1").getHeader(ETAG_HEADER);
        String content2 = "{ \"foo2\": \"bar2\" }";
        given().body(content2).when().put("resources/res1").then().assertThat().statusCode(200);
        when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, not(equalTo(etag)))
                .header(ETAG_HEADER, not(equalTo("")))
                .body(equalTo(content2))
                .statusCode(200);
        async.complete();
    }

    @Test
    public void testEtagPUTWithHeader(TestContext context) {
        Async async = context.async();
        delete();

        String content = "{ \"foo\": \"bar\" }";
        with().body(content).put("resources/res1");
        String etag = get("resources/res1").getHeader(ETAG_HEADER);
        String content2 = "{ \"foo2\": \"bar2\" }";
        given().header(IF_NONE_MATCH_HEADER, etag).body(content2).when().put("resources/res1").then().assertThat().statusCode(304);
        when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag))
                .header(ETAG_HEADER, not(equalTo("")))
                .body(equalTo(content))
                .statusCode(200);
        async.complete();
    }

    @Test
    public void testEtagAndExpiryPUTWithHeader(TestContext context) {
        Async async = context.async();
        delete();

        String content = "{ \"foo\": \"bar\" }";
        with().body(content).put("resources/res1");
        String etag = get("resources/res1").getHeader(ETAG_HEADER);
        String content2 = "{ \"foo2\": \"bar2\" }";

        // Test with an x-expire-after header. Expecting a code 200 and update of the data
        given().header(IF_NONE_MATCH_HEADER, etag).header(EXPIRE_AFTER_HEADER, "1000").body(content2).when().put("resources/res1").then().assertThat().statusCode(200);
        when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag))
                .header(ETAG_HEADER, not(equalTo("")))
                .body(equalTo(content2))
                .statusCode(200);

        // Test with no x-expire-after header. Expecting a code 304 (not modified) and no update of the data
        etag = get("resources/res1").getHeader(ETAG_HEADER);
        String content3 = "{ \"foo3\": \"bar3\" }";
        given().header(IF_NONE_MATCH_HEADER, etag).body(content3).when().put("resources/res1").then().assertThat().statusCode(304);
        when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag))
                .header(ETAG_HEADER, not(equalTo("")))
                .body(equalTo(content2))
                .statusCode(200);

        // Test with large x-expire-after header => equals maximum expiry. Expecting again a code 200 and update of the data
        etag = get("resources/res1").getHeader(ETAG_HEADER);
        String content4 = "{ \"foo4\": \"bar4\" }";
        given().header(IF_NONE_MATCH_HEADER, etag).header(EXPIRE_AFTER_HEADER, MAX_EXPIRE_IN_MILLIS).body(content4).when().put("resources/res1").then().assertThat().statusCode(200);
        when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag))
                .header(ETAG_HEADER, not(equalTo("")))
                .body(equalTo(content4))
                .statusCode(200);
        async.complete();
    }

    @Test
    public void testInitialEtagPUT(TestContext context) {
        Async async = context.async();
        delete();

        String content = "{ \"foo\": \"bar\" }";
        with().header(IF_NONE_MATCH_HEADER, "myFancyEtagValue").body(content).put("resources/res1");
        String etag = get("resources/res1").getHeader(ETAG_HEADER);
        context.assertEquals("myFancyEtagValue", etag);
        String content2 = "{ \"foo2\": \"bar2\" }";
        given().header(IF_NONE_MATCH_HEADER, etag).body(content2).when().put("resources/res1").then().assertThat().statusCode(304);
        when().get("resources/res1").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag))
                .header(ETAG_HEADER, not(equalTo("")))
                .body(equalTo(content))
                .statusCode(200);
        async.complete();
    }
}
