package org.swisspush.gateleen.core.http;

import com.jayway.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import static com.jayway.restassured.RestAssured.*;
import static org.junit.Assert.assertEquals;

@RunWith(VertxUnitRunner.class)
public class PathLevelTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath("/server/tests");
        RestAssured.requestSpecification.baseUri("http://localhost:" + MAIN_PORT + ROOT);
    }

    public void init() {
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        TestUtils.putRoutingRules(rules);
    }

    @Test
    public void testTryToPutResourceOverCollection(TestContext context) {
        Async async = context.async();
        init();

        RestAssured.requestSpecification.basePath("");

        // here we assume, that on the path server is already a collection
        given().body("{ \"foo\": \"bar\" }").when().put("server").then().assertThat().statusCode(405);

        async.complete();
    }

    @Test
    public void testPut4levels(TestContext context) {
        Async async = context.async();
        init();

        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1/test2/test3");

        given().body("{ \"foo\": \"bar\" }").when().put("test4").then().assertThat().statusCode(200);

        // test level 1 with and without trailing slash
        RestAssured.requestSpecification.basePath("/server/tests/gateleen");
        assertEquals("[test2/]", get("/test1").body().jsonPath().get("test1").toString());
        RestAssured.requestSpecification.basePath("/server/tests/gateleen");
        assertEquals("[test2/]", get("/test1/").body().jsonPath().get("test1").toString());

        // test level 2 with and without trailing slash
        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1/test2");
        assertEquals("[test3/]", get("").body().jsonPath().get("test2").toString());
        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1/test2/");
        assertEquals("[test3/]", get("").body().jsonPath().get("test2").toString());

        // test level 3 with and without trailing slash
        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1/test2/test3");
        assertEquals("[test4]", get("").body().jsonPath().get("test3").toString());
        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1/test2/test3/");
        assertEquals("[test4]", get("").body().jsonPath().get("test3").toString());

        // test4 level
        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1/test2/test3/test4");
        assertEquals("{ \"foo\": \"bar\" }", get("").body().asString());

        async.complete();
    }

    @Test
    public void testPutResourceOverCollection(TestContext context) {
        Async async = context.async();
        init();

        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1/test2/test3");

        given().body("{ \"foo\": \"bar\" }").when().put("test4").then().assertThat().statusCode(200);

        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1");

        given().body("{ \"foo\": \"bar\" }").when().put("test2").then().assertThat().statusCode(405);

        async.complete();
    }

    @Test
    public void testPutCollectionOverResource(TestContext context) {
        Async async = context.async();
        init();

        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1");

        given().body("{ \"foo\": \"bar\" }").when().put("test2").then().assertThat().statusCode(200);

        RestAssured.requestSpecification.basePath("/server/tests/gateleen/test1/test2/test3");

        given().body("{ \"foo\": \"bar\" }").when().put("test4").then().assertThat().statusCode(405);

        async.complete();
    }

}
