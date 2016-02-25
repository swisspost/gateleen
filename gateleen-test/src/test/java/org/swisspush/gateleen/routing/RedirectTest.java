package org.swisspush.gateleen.routing;

import static com.jayway.restassured.RestAssured.delete;
import static com.jayway.restassured.RestAssured.get;
import static com.jayway.restassured.RestAssured.given;
import java.io.File;

import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import io.vertx.core.json.JsonObject;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.http.ContentType;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class RedirectTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT + "/pages");
    }

    private void init() {
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        TestUtils.putRoutingRules(rules);

        // add file to check
        given().multiPart(new File("classpath:empty.html")).put("empty.html").then().assertThat().statusCode(200);
    }

    @Test
    public void testGetHTMLResourceWithoutTrailingSlash(TestContext context) throws InterruptedException {
        Async async = context.async();
        init();
        get("empty.html").then().assertThat().statusCode(200).assertThat().contentType(ContentType.HTML);
        async.complete();
    }

    @Test
    public void testGetHTMLResourceWithTrailingSlash(TestContext context) throws InterruptedException {
        Async async = context.async();
        init();
        get("empty.html/").then().assertThat().statusCode(200).assertThat().contentType(ContentType.HTML);
        async.complete();
    }
}
