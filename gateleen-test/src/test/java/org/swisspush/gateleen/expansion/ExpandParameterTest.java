package org.swisspush.gateleen.expansion;

import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.core.util.StatusCode;

import static io.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItems;

/**
 * Tests the expand feature with focus to the expand parameter values.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ExpandParameterTest extends AbstractTest {

    @Test
    public void testInvalidExpandParameter(TestContext context) {
        Async async = context.async();

        given().param("expand", "foo").when().get("resources/").then().assertThat()
                .statusCode(StatusCode.BAD_REQUEST.getStatusCode())
                .body(containsString("Expand parameter is not valid. Must be a positive number"));

        given().param("expand", -1).when().get("resources/").then().assertThat()
                .statusCode(StatusCode.BAD_REQUEST.getStatusCode())
                .body(containsString("Expand parameter is not valid. Must be a positive number"));

        async.complete();
    }

    /**
     * the maximum expand level hard is configured in the {@link AbstractTest} class to a value of 100
     *
     * @param context test context
     */
    @Test
    public void testMaximumExpandLevelHardExceeded(TestContext context) {
        Async async = context.async();

        given().param("expand", 100).when().get("resources/").then().assertThat()
                .statusCode(StatusCode.NOT_FOUND.getStatusCode());

        given().param("expand", 101).when().get("resources/").then().assertThat()
                .statusCode(StatusCode.BAD_REQUEST.getStatusCode())
                .body(containsString("Expand level '101' is greater than the maximum expand level '100'"));

        given().param("expand", 3000).when().get("resources/").then().assertThat()
                .statusCode(StatusCode.BAD_REQUEST.getStatusCode())
                .body(containsString("Expand level '3000' is greater than the maximum expand level '100'"));

        async.complete();
    }

    /**
     * the maximum expand level soft is configured in the {@link AbstractTest} class to a value of 4
     *
     * @param context test context
     */
    @Test
    public void testMaximumExpandLevelSoftExceeded(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"r_1\" }").put("resources/level1/level2/level3/level4/level5/level6/res_1");
        with().body("{ \"foo\": \"r_2\" }").put("resources/level1/level2/level3/level4/level5/level6/res_2");
        with().body("{ \"foo\": \"r_3\" }").put("resources/level1/level2/level3/level4/level5/level6/res_3");
        with().body("{ \"foo\": \"r_4\" }").put("resources/level1/level2/level3/level4/level5/level6/res_4");

        given().param("expand", 1).when().get("resources/").then().assertThat()
                .statusCode(200)
                .body("resources.level1", hasItems("level2/"));

        given().param("expand", 2).when().get("resources/").then().assertThat()
                .statusCode(200)
                .body("resources.level1.level2", hasItems("level3/"));

        given().param("expand", 3).when().get("resources/").then().assertThat()
                .statusCode(200)
                .body("resources.level1.level2.level3", hasItems("level4/"));

        given().param("expand", 4).when().get("resources/").then().assertThat()
                .statusCode(200)
                .body("resources.level1.level2.level3.level4", hasItems("level5/"));

        /*
         * All expand values above 4 will return expanded data to level 4 only
         */

        given().param("expand", 5).when().get("resources/").then().assertThat()
                .statusCode(200)
                .body("resources.level1.level2.level3.level4", hasItems("level5/"));

        given().param("expand", 10).when().get("resources/").then().assertThat()
                .statusCode(200)
                .body("resources.level1.level2.level3.level4", hasItems("level5/"));

        given().param("expand", 100).when().get("resources/").then().assertThat()
                .statusCode(200)
                .body("resources.level1.level2.level3.level4", hasItems("level5/"));

        /*
         * move some levels deeper and test again
         */

        given().param("expand", 5).when().get("resources/level1/").then().assertThat()
                .statusCode(200)
                .body("level1.level2.level3.level4.level5", hasItems("level6/"));

        given().param("expand", 5).when().get("resources/level1/level2/").then().assertThat()
                .statusCode(200)
                .body("level2.level3.level4.level5.level6", hasItems("res_1", "res_2", "res_3", "res_4"));

        given().param("expand", 4).when().get("resources/level1/level2/").then().assertThat()
                .statusCode(200)
                .body("level2.level3.level4.level5.level6", hasItems("res_1", "res_2", "res_3", "res_4"));

        given().param("expand", 3).when().get("resources/level1/level2/").then().assertThat()
                .statusCode(200)
                .body("level2.level3.level4.level5", hasItems("level6/"));

        given().param("expand", 4).when().get("resources/level1/level2/level3/").then().assertThat()
                .statusCode(200)
                .body("level3.level4.level5.level6.res_1.foo", equalTo("r_1"))
                .body("level3.level4.level5.level6.res_2.foo", equalTo("r_2"))
                .body("level3.level4.level5.level6.res_3.foo", equalTo("r_3"))
                .body("level3.level4.level5.level6.res_4.foo", equalTo("r_4"));

        async.complete();
    }
}
