package org.swisspush.gateleen.expansion;

import org.swisspush.gateleen.AbstractTest;
import io.restassured.response.Response;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static io.restassured.RestAssured.*;

/**
 * Tests the expand feature with the limit and offset parameter.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class ExpandOffsetLimitTest extends AbstractTest {

    @Test
    public void testExpandWithLimit(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"bar1\" }").put("resources/res1");
        with().body("{ \"foo\": \"bar2\" }").put("resources/res2");

        // only one resource should be available
        Response response = given().param("expand", 1).param("limit", 1).get("resources/");
        context.assertEquals("bar1", response.getBody().jsonPath().get("resources.res1.foo"));
        context.assertNull(response.getBody().jsonPath().get("resources.res2"));

        // two (both) resources should be available
        response = given().param("expand", 1).param("limit", 2).get("resources/");
        context.assertEquals("bar1", response.getBody().jsonPath().get("resources.res1.foo"));
        context.assertEquals("bar2", response.getBody().jsonPath().get("resources.res2.foo"));

        async.complete();
    }

    @Test
    public void testExpandWithOffset(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"bar1\" }").put("resources/res1");
        with().body("{ \"foo\": \"bar2\" }").put("resources/res2");
        with().body("{ \"foo\": \"bar3\" }").put("resources/res3");
        with().body("{ \"foo\": \"bar4\" }").put("resources/res4");

        // 4 resources should be available
        Response response = given().param("expand", 1).param("offset", 0).get("resources/");
        context.assertEquals("bar1", response.getBody().jsonPath().get("resources.res1.foo"));
        context.assertEquals("bar2", response.getBody().jsonPath().get("resources.res2.foo"));
        context.assertEquals("bar3", response.getBody().jsonPath().get("resources.res3.foo"));
        context.assertEquals("bar4", response.getBody().jsonPath().get("resources.res4.foo"));

        // 3 resources should be available
        response = given().param("expand", 1).param("offset", 1).get("resources/");
        context.assertNull(response.getBody().jsonPath().get("resources.res1"));
        context.assertEquals("bar2", response.getBody().jsonPath().get("resources.res2.foo"));
        context.assertEquals("bar3", response.getBody().jsonPath().get("resources.res3.foo"));
        context.assertEquals("bar4", response.getBody().jsonPath().get("resources.res4.foo"));

        // 1 resource should be available
        response = given().param("expand", 1).param("offset", 3).get("resources/");
        context.assertNull(response.getBody().jsonPath().get("resources.res1"));
        context.assertNull(response.getBody().jsonPath().get("resources.res2"));
        context.assertNull(response.getBody().jsonPath().get("resources.res3"));
        context.assertEquals("bar4", response.getBody().jsonPath().get("resources.res4.foo"));

        async.complete();
    }

    @Test
    @SuppressWarnings("rawtypes")
    public void testExpandWithLimitAndOffset(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"bar1\" }").put("resources/res1");
        with().body("{ \"foo\": \"bar2\" }").put("resources/res2");
        with().body("{ \"foo\": \"bar3\" }").put("resources/res3");
        with().body("{ \"foo\": \"bar4\" }").put("resources/res4");

        // all resources should be available
        Response response = given().param("expand", 1).param("offset", 0).param("limit", 4).get("resources/");
        context.assertEquals("bar1", response.getBody().jsonPath().get("resources.res1.foo"));
        context.assertEquals("bar2", response.getBody().jsonPath().get("resources.res2.foo"));
        context.assertEquals("bar3", response.getBody().jsonPath().get("resources.res3.foo"));
        context.assertEquals("bar4", response.getBody().jsonPath().get("resources.res4.foo"));

        // only resource nr. 2 und 3 should be available
        response = given().param("expand", 1).param("offset", 1).param("limit", 2).get("resources/");
        context.assertNull(response.getBody().jsonPath().get("resources.res1"));
        context.assertEquals("bar2", response.getBody().jsonPath().get("resources.res2.foo"));
        context.assertEquals("bar3", response.getBody().jsonPath().get("resources.res3.foo"));
        context.assertNull(response.getBody().jsonPath().get("resources.res4"));

        // only resource nr. 2, 3, und 4 should be available
        response = given().param("expand", 1).param("offset", 1).param("limit", 4).get("resources/");
        context.assertNull(response.getBody().jsonPath().get("resources.res1"));
        context.assertEquals("bar2", response.getBody().jsonPath().get("resources.res2.foo"));
        context.assertEquals("bar3", response.getBody().jsonPath().get("resources.res3.foo"));
        context.assertEquals("bar4", response.getBody().jsonPath().get("resources.res4.foo"));

        // only resource 1 and 2 should be available
        response = given().param("expand", 1).param("offset", 0).param("limit", 2).get("resources/");
        context.assertEquals("bar1", response.getBody().jsonPath().get("resources.res1.foo"));
        context.assertEquals("bar2", response.getBody().jsonPath().get("resources.res2.foo"));
        context.assertNull(response.getBody().jsonPath().get("resources.res3"));
        context.assertNull(response.getBody().jsonPath().get("resources.res4"));

        // only resource 2 and 3 should be available
        response = given().param("expand", 1).param("offset", 1).param("limit", 2).get("resources/");
        context.assertNull(response.getBody().jsonPath().get("resources.res1"));
        context.assertEquals("bar2", response.getBody().jsonPath().get("resources.res2.foo"));
        context.assertEquals("bar3", response.getBody().jsonPath().get("resources.res3.foo"));
        context.assertNull(response.getBody().jsonPath().get("resources.res4"));

        // only resource 3 and 4 should be available
        response = given().param("expand", 1).param("offset", 2).param("limit", 2).get("resources/");
        context.assertNull(response.getBody().jsonPath().get("resources.res1"));
        context.assertNull(response.getBody().jsonPath().get("resources.res2"));
        context.assertEquals("bar3", response.getBody().jsonPath().get("resources.res3.foo"));
        context.assertEquals("bar4", response.getBody().jsonPath().get("resources.res4.foo"));

        // none resource should be available (limit 0)
        response = given().param("expand", 1).param("offset", 0).param("limit", 0).get("resources/");
        context.assertEquals(0, ((List) response.getBody().jsonPath().get("resources")).size());

        // none resource should be available (offset > 3)
        response = given().param("expand", 1).param("offset", 4).param("limit", 4).get("resources/");
        context.assertEquals(0, ((List) response.getBody().jsonPath().get("resources")).size());

        async.complete();
    }
}
