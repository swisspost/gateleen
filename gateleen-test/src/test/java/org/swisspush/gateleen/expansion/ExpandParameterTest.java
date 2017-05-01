package org.swisspush.gateleen.expansion;

import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.core.util.StatusCode;

import static com.jayway.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

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
     * the maximum expand level is configured in the {@link AbstractTest} class to a value of 100
     *
     * @param context test context
     */
    @Test
    public void testMaximumExpandLevelExceeded(TestContext context) {
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

}
