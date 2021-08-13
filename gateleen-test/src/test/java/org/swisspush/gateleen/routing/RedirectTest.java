package org.swisspush.gateleen.routing;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;

import java.io.File;
import java.io.IOException;

import static io.restassured.RestAssured.*;

@RunWith(VertxUnitRunner.class)
public class RedirectTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT + "/pages");
    }

    private void init() throws IOException {
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        TestUtils.putRoutingRules(rules);

        // create a simple temp file
        File empty = File.createTempFile("redirect-test", "empty.html");

        // add file to check
        given().contentType("multipart/json").multiPart(empty).put("empty.html").then().assertThat().statusCode(200);
    }

    @Test
    public void testGetHTMLResourceWithoutTrailingSlash(TestContext context) throws IOException {
        Async async = context.async();
        init();
        get("empty.html").then().assertThat().statusCode(200).assertThat().contentType(ContentType.HTML);
        async.complete();
    }

    @Test
    public void testGetHTMLResourceWithTrailingSlash(TestContext context) throws IOException {
        Async async = context.async();
        init();
        get("empty.html/").then().assertThat().statusCode(200).assertThat().contentType(ContentType.HTML);
        async.complete();
    }
}
