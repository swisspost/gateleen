package org.swisspush.gateleen.delta;

import org.swisspush.gateleen.AbstractTest;
import com.jayway.restassured.RestAssured;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.*;

@RunWith(VertxUnitRunner.class)
public class OffsetTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);
    }

    @Test
    public void testInvalidOffsets(TestContext context) {
        Async async = context.async();
        delete();

        for (int i = 1; i <= 10; i++) {
            with().body("{ \"foo\": \"bar" + i + "\" }")
                    .put("resources/res" + i);
        }

        // get with invalid offsets
        given().param("delta", 0).when().get("resources/?limit=bla")
                .then().assertThat().body("resources", hasItem("res10"));

        given().param("delta", 0).when().get("resources/?offset=bla")
                .then().assertThat().body("resources", hasItem("res10"));

        given().param("delta", 0).when().get("resources/?offset=bla&limit=blo")
                .then().assertThat().body("resources", hasItem("res10"));

        given().param("delta", 0).when().get("resources/?offset=-99&limit-1")
                .then().assertThat().body("resources", hasItem("res10"));

        given().param("delta", 0).when().get("resources/?offset=-1&limit=-1")
                .then().assertThat().body("resources", hasItem("res10"));
        async.complete();
    }

    @Test
    public void testValidLimits(TestContext context) {
        Async async = context.async();
        delete();

        for (int i = 1; i <= 10; i++) {
            with().body("{ \"foo\": \"bar" + i + "\" }")
                    .put("resources/res" + i);
        }

        // get with valid offsets
        given().param("delta", 0).when().get("resources/?limit=10")
                .then().assertThat()
                .body("resources", hasItems("res1", "res2", "res3", "res4", "res5", "res6", "res7", "res8", "res9", "res10"));

        given().param("delta", 0).when().get("resources?limit=99")
                .then().assertThat()
                .body("resources", hasItems("res1", "res2", "res3", "res4", "res5", "res6", "res7", "res8", "res9", "res10"));

        given().param("delta", 0).when().get("resources?limit=5")
                .then().assertThat()
                .body("resources", hasItems("res10", "res1", "res2", "res3", "res4"))
                .body("resources", not(hasItems("res5", "res6", "res7", "res8", "res9")));

        given().param("delta", 0).when().get("resources?limit=8")
                .then().assertThat()
                .body("resources", hasItems("res1", "res10", "res2", "res3", "res4", "res5", "res7"))
                .body("resources", not(hasItems("res8", "res9")));
        async.complete();
    }

    @Test
    public void testValidOffsets(TestContext context) {
        Async async = context.async();
        delete();

        for (int i = 1; i <= 10; i++) {
            with().body("{ \"foo\": \"bar" + i + "\" }")
                    .put("resources/res" + i);
        }

        // get with valid offsets
        given().param("delta", 0).when().get("resources/?offset=2")
                .then().assertThat()
                .body("resources", not(hasItems("res10", "res1")))
                .body("resources", hasItems("res2", "res3", "res4", "res5", "res6", "res7", "res8", "res9"));

        given().param("delta", 0).when().get("resources?offset=0")
                .then().assertThat()
                .body("resources", hasItems("res1", "res2", "res3", "res4", "res5", "res6", "res7", "res8", "res9", "res10"));

        given().param("delta", 0).when().get("resources?offset=5")
                .then().assertThat()
                .body("resources", not(hasItems("res10", "res1", "res2", "res3", "res4")))
                .body("resources", hasItems("res5", "res6", "res7", "res8", "res9"));

        given().param("delta", 0).when().get("resources?offset=11")
                .then().assertThat()
                .body("resources", not(hasItems("res1", "res2", "res3", "res4", "res5", "res6", "res7", "res8", "res9", "res10")));
        async.complete();
    }

    @Test
    public void testInvalidLimitsOffsets(TestContext context) {
        Async async = context.async();
        delete();

        for (int i = 1; i <= 10; i++) {
            with().body("{ \"foo\": \"bar" + i + "\" }")
                    .put("resources/res" + i);
        }

        // get with valid offsets
        given().param("delta", 0).when().get("resources/?offset=2&limit=bla")
                .then().assertThat()
                .body("resources", hasItems("res2", "res3", "res4", "res5", "res6", "res7", "res8", "res9"));

        given().param("delta", 0).when().get("resources?offset=bla&limit=3")
                .then().assertThat()
                .body("resources", hasItems("res1", "res10", "res2"));

        given().param("delta", 0).when().get("resources?offset=1-5&limit=5")
                .then().assertThat()
                .body("resources", hasItems("res1", "res10", "res2", "res3", "res4"));

        given().param("delta", 0).when().get("resources?offset=99&limit=4")
                .then().assertThat()
                .body("resources", not(hasItems("res1", "res2", "res3", "res4", "res5", "res6", "res7", "res8", "res9", "res10")));
        async.complete();
    }

    @Test
    public void testValidLimitsOffsets(TestContext context) {
        Async async = context.async();
        delete();

        for (int i = 1; i <= 10; i++) {
            with().body("{ \"foo\": \"bar" + i + "\" }")
                    .put("resources/res" + i);
        }

        // get with valid offsets
        given().param("delta", 0).when().get("resources/?offset=2&limit=-1")
                .then().assertThat()
                .body("resources", hasItems("res2", "res3", "res4", "res5", "res6", "res7", "res8", "res9"));

        given().param("delta", 0).when().get("resources?offset=0&limit=3")
                .then().assertThat()
                .body("resources", hasItems("res1", "res10", "res2"));

        given().param("delta", 0).when().get("resources?offset=2&limit=2")
                .then().assertThat()
                .body("resources", hasItems("res2", "res3"));

        given().param("delta", 0).when().get("resources?offset=1&limit=10")
                .then().assertThat()
                .body("resources", hasItems("res10", "res2", "res3", "res4", "res5", "res6", "res7", "res8", "res9"));
        async.complete();
    }

}
