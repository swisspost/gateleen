package org.swisspush.gateleen.core.storage;

import static io.restassured.RestAssured.delete;
import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.put;
import static io.restassured.RestAssured.with;
import static org.junit.Assert.*;

import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import io.vertx.core.json.JsonObject;
import io.restassured.RestAssured;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class CleanupTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(ROOT);
        RestAssured.requestSpecification.baseUri("http://localhost:" + MAIN_PORT + ROOT);
    }

    public void init() {
        delete();

        // add a routing
        JsonObject rules = new JsonObject();
        rules = TestUtils.addRoutingRuleMainStorage(rules);
        rules = TestUtils.addRoutingRuleCleanup(rules);
        TestUtils.putRoutingRules(rules);

        RestAssured.requestSpecification.basePath("");
    }

    @Test
    public void testCleanup100(TestContext context) throws InterruptedException {
        Async async = context.async();
        init();

        put("/server/queuing/locks/scheduler-main-storage-cleanup");

        try {

            with().body("").post("_cleanup");

            RestAssured.basePath = "/server/tests/cleanup/test1";
            for (int i = 1; i <= 100; i++) {

                with().body("{ \"foo\": \"bar" + i + "exp\" }").header("x-delta", "auto").header("x-expire-after", "2").put("test" + i);
            }

            RestAssured.basePath = "/server/tests/test1";

            TestUtils.waitSomeTime(3);

            RestAssured.basePath = "";

            assertEquals("100",
                    with().body("").post("_cleanup").body().jsonPath().get("cleanedResources").toString());

            RestAssured.basePath = "/server/tests/cleanup";
            get("test1").then().assertThat().statusCode(404);

        } finally {
            delete("/server/queuing/locks/scheduler-main-storage-cleanup");
            async.complete();
        }
    }

    @Test
    public void testCleanup2050(TestContext context) throws InterruptedException {
        Async async = context.async();
        init();

        put("/server/queuing/locks/scheduler-main-storage-cleanup");

        try {

            with().body("").post("_cleanup");

            RestAssured.basePath = "/server/tests/cleanup/test1";
            for (int i = 1; i <= 2050; i++) {

                with().body("{ \"foo\": \"bar" + i + "exp\" }").header("x-delta", "auto").header("x-expire-after", "2").put("test" + i);
            }

            RestAssured.basePath = "/server/tests/cleanup/test1";

            TestUtils.waitSomeTime(3);

            RestAssured.basePath = "";

            // run cleanup task
            with().body("").post("_cleanup");

            TestUtils.waitSomeTime(2);

            /*
             * run cleanup task again. There should not be many cleaned resources anymore,
             * since we may also have scheduled cleanup tasks running
             */
            String cleanedResourcesStr = with().body("").post("_cleanup").body().jsonPath().get("cleanedResources").toString();

            try {
                Integer cleanedResources = Integer.parseInt(cleanedResourcesStr);
                assertTrue("After (at least) two cleanup tasks we should not have cleaned more than 50 resources", cleanedResources <= 50);
            } catch (NumberFormatException ex) {
                fail("cleanedResources does not contain a numerical value but '" + cleanedResourcesStr + "'");
            }

        } finally {
            delete("/server/queuing/locks/scheduler-main-storage-cleanup");
            async.complete();
        }
    }

    @Test
    public void testCleanup1070CleanupResourceAmount1000(TestContext context) throws InterruptedException {
        Async async = context.async();
        init();

        put("/server/queuing/locks/scheduler-main-storage-cleanup");

        try {

            with().body("").post("_cleanup");

            RestAssured.basePath = "/server/tests/cleanup/test1";
            for (int i = 1; i <= 1070; i++) {

                with().body("{ \"foo\": \"bar" + i + "exp\" }").header("x-delta", "auto").header("x-expire-after", "2").put("test" + i);
            }

            RestAssured.basePath = "/server/tests/cleanup/test1?cleanupResourcesAmount=1000";

            TestUtils.waitSomeTime(3);

            RestAssured.basePath = "";

            String cleanedResourcesStr = with().body("").queryParam("cleanupResourcesAmount", "1000").post("_cleanup").body().jsonPath().get("cleanedResources").toString();
            try {
                Integer cleanedResources = Integer.parseInt(cleanedResourcesStr);
                assertTrue("The cleanup task should not have cleaned more than 1000 resources", cleanedResources <= 1000);
            } catch (NumberFormatException ex) {
                fail("cleanedResources does not contain a numerical value but '" + cleanedResourcesStr + "'");
            }

        } finally {
            delete("/server/queuing/locks/scheduler-main-storage-cleanup");
            async.complete();
        }
    }
}
