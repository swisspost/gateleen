package org.swisspush.gateleen.core.storage;

import org.swisspush.gateleen.AbstractTest;
import com.jayway.restassured.RestAssured;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.awaitility.Duration.FIVE_SECONDS;
import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.equalTo;

@RunWith(VertxUnitRunner.class)
public class ExpirationTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath("/server/tests/expiration");
        RestAssured.requestSpecification.baseUri("http://localhost:" + MAIN_PORT + ROOT);
    }

    @Test
    public void testPutInvalidExpireFloat(TestContext context) {
        Async async = context.async();
        delete();

        given().header("x-expire-after", "1.22").body("{ \"foo\": \"bar1\" }").when().put("expireisfloat").then().assertThat().statusCode(400);

        when().get("expireaftertwoseconds").then().assertThat().statusCode(404);

        async.complete();
    }

    @Test
    public void testPutInvalidExpireNaN(TestContext context) {
        Async async = context.async();
        delete();

        given().header("x-expire-after", "asdfasdf").body("{ \"foo\": \"bar1\" }").when().put("invalidExpireNan").then().assertThat().statusCode(400);

        when().get("invalidExpireNan").then().assertThat().statusCode(404);

        async.complete();
    }

    @Test
    public void testPutExpireAfterOneSecond(TestContext context) {
        Async async = context.async();
        delete();

        given().header("x-expire-after", "1").body("{ \"foo\": \"bar1\" }").when().put("expireaftertwoseconds").then().assertThat().statusCode(200);

        await().atMost(FIVE_SECONDS).until(() -> get("expireaftertwoseconds").statusCode(), equalTo(404));

        async.complete();
    }

    @Test
    public void testPutAfterExpiration(TestContext context) {
        Async async = context.async();
        delete();

        given().header("x-expire-after", "1").body("{ \"foo\": \"bar1\" }").when().put("putafterexpiration").then().assertThat().statusCode(200);

        await().atMost(5, TimeUnit.SECONDS).until(() -> get("putafterexpiration").statusCode(), equalTo(404));

        given().header("x-expire-after", "10").body("{ \"foo\": \"bar2\" }").when().put("putafterexpiration").then().assertThat().statusCode(200);

        when().get("putafterexpiration").then().statusCode(200).body("foo", equalTo("bar2"));

        async.complete();
    }

    @Test
    public void testPutAfterImmediateExpiration(TestContext context) {
        Async async = context.async();
        delete();

        given().header("x-expire-after", "0").body("{ \"foo\": \"bar1\" }").when().put("expireimmediatly").then().assertThat().statusCode(200);

        await().atMost(FIVE_SECONDS).until(() -> get("expireimmediatly").statusCode(), equalTo(404));

        given().header("x-expire-after", "10").body("{ \"foo\": \"bar2\" }").when().put("expireimmediatly").then().assertThat().statusCode(200);

        when().get("/expireimmediatly").then().statusCode(200).body("foo", equalTo("bar2"));

        async.complete();
    }

    @Test
    public void testPutMultipleBranchesAfterImmediateExpiration(TestContext context) {
        Async async = context.async();
        delete();

        given().header("x-expire-after", "0").body("{ \"foo\": \"bar1\" }").when().put("resexpireimmediatly/branch1").then().assertThat().statusCode(200);

        given().header("x-expire-after", "0").body("{ \"foo\": \"bar2\" }").when().put("resexpireimmediatly/branch2").then().assertThat().statusCode(200);

        given().header("x-expire-after", "0").body("{ \"foo\": \"bar3\" }").when().put("resexpireimmediatly/branch3").then().assertThat().statusCode(200);

        await().atMost(FIVE_SECONDS).until(() -> get("resexpireimmediatly/branch1").statusCode(), equalTo(404));

        when().get("resexpireimmediatly/branch2").then().assertThat().statusCode(404);
        when().get("resexpireimmediatly/branch3").then().assertThat().statusCode(404);

        given().header("x-expire-after", "100").body("{ \"foo\": \"bar11\" }").when().put("resexpireimmediatly/branch1").then().assertThat().statusCode(200);

        given().body("{ \"foo\": \"bar22\" }").when().put("resexpireimmediatly/branch2").then().assertThat().statusCode(200);

        given().header("x-expire-after", "10").body("{ \"foo\": \"bar33\" }").when().put("resexpireimmediatly/branch3").then().assertThat().statusCode(200);

        when().get("resexpireimmediatly/branch1").then().statusCode(200).body("foo", equalTo("bar11"));
        when().get("resexpireimmediatly/branch2").then().statusCode(200).body("foo", equalTo("bar22"));
        when().get("resexpireimmediatly/branch3").then().statusCode(200).body("foo", equalTo("bar33"));

        async.complete();
    }

    @Test
    public void testPutWithNoExpiryAfterImmediateExpiration(TestContext context) {
        Async async = context.async();
        delete();

        given().header("x-expire-after", "0").body("{ \"foo\": \"bar1\" }").when().put("resexpireimmediatly/branch1").then().assertThat().statusCode(200);

        when().get("resexpireimmediatly/branch1").then().assertThat().statusCode(404);

        given().body("{ \"foo\": \"bar11\" }").when().put("resexpireimmediatly/branch1").then().assertThat().statusCode(200);

        when().get("resexpireimmediatly/branch1").then().statusCode(200).body("foo", equalTo("bar11"));

        async.complete();
    }
}
