package org.swisspush.gateleen.expansion;

import org.junit.Assert;
import org.swisspush.gateleen.AbstractTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.awaitility.Duration.TEN_SECONDS;
import static io.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the expand feature with the delta parameter.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class ExpandDeltaTest extends AbstractTest {

    private final String ETAG_HEADER = "Etag";
    private final String IF_NONE_MATCH_HEADER = "if-none-match";
    private final String ORIGIN_ADDRESS = "http://127.0.0.1:8888";
    private final String CORS_HEADER_CREDENTIALS = "Access-Control-Allow-Credentials";
    private final String CORS_HEADER_METHODS = "Access-Control-Allow-Methods";
    private final String CORS_HEADER_ORIGIN = "Access-Control-Allow-Origin";

    @Test
    public void testExpand(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"bar1\" }").put("resources/res1");
        with().body("{ \"foo\": \"bar2\" }").put("resources/res2");
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .body("resources.res1.foo", equalTo("bar1"))
                .body("resources.res2.foo", equalTo("bar2"));

        async.complete();
    }

    @Test
    public void testExpandWithOneInvalidSubResource(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"bar1\" }").put("resources/goodResource");
        with().body("{ \"foo\": \"bar2 }").put("resources/badResource");
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .statusCode(500)
                .body(containsString("Errors found in resources:"))
                .body(containsString("badResource"))
                .body(not(containsString("goodResource")));

        async.complete();
    }

    @Test
    public void testExpandWithTwoInvalidSubResources(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"good\" }").put("resources/good_1");
        with().body("{ \"foo\": \"bad }").put("resources/bad_1");
        with().body("{ \"foo\": \"bad }").put("resources/bad_2");
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .statusCode(500)
                .body(containsString("Errors found in resources:"))
                .body(containsString("bad_1"))
                .body(containsString("bad_2"))
                .body(not(containsString("good_1")));

        async.complete();
    }

    @Test
    public void testExpandWithNoInvalidSubResources(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"good_1\" }").put("resources/good_1");
        with().body("{ \"foo\": \"good_2\" }").put("resources/good_2");
        with().body("{ \"foo\": \"good_3\" }").put("resources/good_3");
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .statusCode(200)
                .body(not(containsString("Errors found in resources:")));

        async.complete();
    }

    @Test
    public void testExpandEtag(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"bar1\" }").put("resources/res1");
        with().body("{ \"foo\": \"bar2\" }").put("resources/res2");

        String etag = given().param("expand", 1).when().get("resources/").getHeader(ETAG_HEADER);
        assertNotNull("Etag header should be available in response headers", etag);
        assertTrue("Etag header should not be empty", etag.length() > 0);

        // multiple requests to unchanged resources should return the same Etag
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag));
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .header(ETAG_HEADER, equalTo(etag));

        // modify a resource; Etag should now be different
        with().body("{ \"foo\": \"barX\" }").put("resources/res1");
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .header(ETAG_HEADER, not(equalTo(etag)));

        // next requests should result in the new Etag header
        String etagNew = given().param("expand", 1).when().get("resources/").getHeader(ETAG_HEADER);
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .header(ETAG_HEADER, equalTo(etagNew));
        given().param("expand", 1).when().get("resources/").then().assertThat()
                .header(ETAG_HEADER, equalTo(etagNew));

        async.complete();
    }

    @Test
    public void testExpandEtagNotModified(TestContext context) {
        Async async = context.async();
        delete();
        with().body("{ \"foo\": \"bar1\" }").put("resources/res1");
        with().body("{ \"foo\": \"bar2\" }").put("resources/res2");
        given().param("expand", 1).when().get("resources/");

        String etag = given().param("expand", 1).when().get("resources/").getHeader(ETAG_HEADER);
        assertNotNull("Etag header should be available in response headers", etag);
        assertTrue("Etag header should not be empty", etag.length() > 0);

        // when if-none-match header not provided. Data should be loaded => Statuscode 200
        given().param("expand", 1).when().get("resources/").then().assertThat().statusCode(200);

        // when if-none-match header provided. Data should not be loaded => Statuscode 304
        given().param("expand", 1).header(IF_NONE_MATCH_HEADER, etag).when().get("resources/").then().assertThat().statusCode(304);

        // modify any resource. this should change the etag, so providing the "old" etag should result in downloading data => Statuscode 200
        with().body("{ \"foo\": \"barX\" }").put("resources/res2");
        given().param("expand", 1).header(IF_NONE_MATCH_HEADER, etag).when().get("resources/").then().assertThat().statusCode(200);

        async.complete();
    }

    @Test
    public void testDelta(TestContext context) {
        Async async = context.async();
        delete();

        // add first resource
        with().body("{ \"foo\": \"bar1\" }").header("x-delta", "auto")
                .put("resources/res1");

        // first get
        String updateId1 = given().param("delta", 0).when().get("resources/")
                .then().assertThat().header("x-delta", notNullValue())
                .body("resources", hasItem("res1")).extract().header("x-delta");

        // add second resource
        with().body("{ \"foo\": \"bar2\" }").header("x-delta", "auto")
                .put("resources/res2");

        // second get with delta
        String updateId2 = given().param("delta", updateId1).when()
                .get("resources/").then().assertThat()
                .header("x-delta", notNullValue())
                .body("resources.size()", equalTo(1))
                .body("resources", hasItem("res2")).extract().header("x-delta");

        // third get with delta
        given().param("delta", updateId2).when().get("resources/").then()
                .assertThat().body("resources.size()", equalTo(0));

        async.complete();
    }

    @Test
    public void testDeltaEtagWithoutIfNoneMatchHeader(TestContext context) {
        Async async = context.async();
        delete();

        // add resources
        with().body("{ \"foo\": \"bar1\" }").header("x-delta", "auto")
                .put("resources/res1");
        with().body("{ \"foo\": \"bar2\" }").header("x-delta", "auto")
                .put("resources/res2");
        with().body("{ \"foo\": \"bar3\" }").header("x-delta", "auto")
                .put("resources/res3");

        String updateId1 = given().param("delta", 0).when().get("resources/")
                .then().assertThat().header("x-delta", notNullValue())
                .body("resources", hasItem("res1")).extract().header("x-delta");

        // modify a resource. This should result in a new x-delta value
        with().body("{ \"foo\": \"bar2_1\" }").header("x-delta", "auto")
                .put("resources/res2");

        String updateId2 = given().param("delta", 0).when().get("resources/")
                .then().assertThat().header("x-delta", notNullValue()).header("x-delta", is(not(equalTo(updateId1))))
                .body("resources", hasItem("res1")).extract().header("x-delta");

        context.assertTrue(Integer.parseInt(updateId2) > Integer.parseInt(updateId1), "new x-delta value should be bigger");

        async.complete();
    }

    @Test
    public void testDeltaEtagWithSameIfNoneMatchHeader(TestContext context) {
        Async async = context.async();
        delete();

        // add resources
        String res1_content1 = "{ \"foo\": \"bar1\" }";
        with().body(res1_content1).header("x-delta", "auto").header(IF_NONE_MATCH_HEADER, "Res1EtagHeader_1")
                .put("resources/res1").then().assertThat().statusCode(200);
        String res2_content1 = "{ \"foo\": \"bar2\" }";
        with().body(res2_content1).header("x-delta", "auto").header(IF_NONE_MATCH_HEADER, "Res2EtagHeader_1")
                .put("resources/res2").then().assertThat().statusCode(200);

        String updateId1 = given().param("delta", 0).when().get("resources/")
                .then().assertThat().header("x-delta", notNullValue())
                .body("resources", hasItem("res1")).extract().header("x-delta");

        // modify a resource with the same etag. This should not result in a new x-delta value
        String res2_content2 = "{ \"foo\": \"bar2_1\" }";
        with().body(res2_content2).header("x-delta", "auto").header(IF_NONE_MATCH_HEADER, "Res2EtagHeader_1")
                .put("resources/res2").then().assertThat().statusCode(304);

        // x-delta value should not have changed
        given().param("delta", 0).when().get("resources/")
                .then().assertThat().header("x-delta", notNullValue()).header("x-delta", is(equalTo(updateId1)))
                .body("resources", hasItem("res1")).extract().header("x-delta");

        // resource should not have changed
        when().get("resources/res2").then().assertThat()
                .header(ETAG_HEADER, equalTo("Res2EtagHeader_1"))
                .header(ETAG_HEADER, not(equalTo("")))
                .body(equalTo(res2_content1))
                .statusCode(200);

        async.complete();
    }

    @Test
    public void testDeltaEtagWithDifferentIfNoneMatchHeader(TestContext context) {
        Async async = context.async();
        delete();

        // add resources
        String res1_content1 = "{ \"foo\": \"bar1\" }";
        with().body(res1_content1).header("x-delta", "auto").header(IF_NONE_MATCH_HEADER, "Res1EtagHeader_1")
                .put("resources/res1").then().assertThat().statusCode(200);
        String res2_content1 = "{ \"foo\": \"bar2\" }";
        with().body(res2_content1).header("x-delta", "auto").header(IF_NONE_MATCH_HEADER, "Res2EtagHeader_1")
                .put("resources/res2").then().assertThat().statusCode(200);

        String updateId1 = given().param("delta", 0).when().get("resources/")
                .then().assertThat().header("x-delta", notNullValue())
                .body("resources", hasItem("res1")).extract().header("x-delta");

        // modify a resource with a different etag. This should result in a new x-delta value
        String res2_content2 = "{ \"foo\": \"bar2_1\" }";
        with().body(res2_content2).header("x-delta", "auto").header(IF_NONE_MATCH_HEADER, "Res2EtagHeader_2")
                .put("resources/res2").then().assertThat().statusCode(200);

        // x-delta value should have changed
        given().param("delta", 0).when().get("resources/")
                .then().assertThat().header("x-delta", notNullValue()).header("x-delta", is(not(equalTo(updateId1))))
                .body("resources", hasItem("res1")).extract().header("x-delta");

        // resource should have changed
        when().get("resources/res2").then().assertThat()
                .header(ETAG_HEADER, equalTo("Res2EtagHeader_2"))
                .header(ETAG_HEADER, not(equalTo("")))
                .body(equalTo(res2_content2))
                .statusCode(200);

        async.complete();
    }

    @Test
    public void testDeltaAndExpand(TestContext context) {
        Async async = context.async();
        delete();

        // add first resource
        with().body("{ \"foo\": \"bar1\" }").header("x-delta", "auto")
                .put("resources/res1");

        // first get
        String updateId1 = given().param("delta", 0).param("expand", 1).when()
                .get("resources").then().assertThat()
                .header("x-delta", notNullValue())
                .body("resources.res1.foo", equalTo("bar1")).extract()
                .header("x-delta");

        // add second resource
        with().body("{ \"foo\": \"bar2\" }").header("x-delta", "auto")
                .put("resources/res2");

        // second get with delta
        String updateId2 = given().param("delta", updateId1).param("expand", 1)
                .when().get("resources").then().assertThat()
                .header("x-delta", notNullValue())
                .body("resources.size()", equalTo(1))
                .body("resources.res2.foo", equalTo("bar2")).extract()
                .header("x-delta");

        // third get with delta
        given().param("delta", updateId2).when().get("resources/").then()
                .assertThat().body("resources.size()", equalTo(0));

        async.complete();
    }

    @Test
    public void testExpandNotAllowedOnNonCollectionResource(TestContext context) {
        Async async = context.async();
        delete();

        with().body("{ \"foo\": \"bar\" }").put("resources/resBadExpand");

        // get resource without expand param. Should work
        get("resources/resBadExpand").then().assertThat().statusCode(200).body("foo", equalTo("bar"));

        // get resource with expand param. Should end in a 400 bad request error
        given().param("expand", 1).when().get("resources/resBadExpand").then().assertThat().statusCode(400).body(containsString("Invalid usage of params"));

        delete("resources/resBadExpand");

        async.complete();
    }

    @Test
    public void testDeltaAndExpire(TestContext context) {
        Async async = context.async();
        delete();

        // add first resource
        with().body("{ \"foo\": \"bar1exp\" }").header("x-delta", "auto")
                .header("x-expire-after", "4").put("resources/res1exp");

        // first get
        given().param("delta", 0).when().get("resources/").then().assertThat()
                .header("x-delta", notNullValue())
                .body("resources", hasItem("res1exp")).extract()
                .header("x-delta");

        await().atMost(TEN_SECONDS).until(() -> String.valueOf(get("resources/res1exp").getStatusCode()), equalTo("404"));

        async.complete();
    }

    // we can't rely in the moment onto this url, cause it depends on the
    // config in routing rules (mapservicedev/mapserviceint) if results are found
    @Ignore
    @Test
    public void testDeltaScamp(TestContext context) {
        Async async = context.async();
        delete();

        RestAssured.basePath = "/scamp/v1/zips/605260/tours/4/persons";
        given().param("delta", "20200303-120000.000").when()
                .get("").then().assertThat()
                .header("X-Delta", notNullValue())
                .body("persons.size()", equalTo(0));

        given().param("delta", "20000303-120000.000").when()
                .get("").then().assertThat()
                .header("X-Delta", notNullValue())
                .body("persons.size()", greaterThan(0));

        async.complete();
    }

    /**
     * Tests a deep expand with delta
     */
    @Test
    public void testAdvancedDeltaExpand(TestContext context) {
        Async async = context.async();
        delete();

        // start structure
        createIdentity("01");
        createCredentials("01");

        Response r = given().get("users?expand=100&delta=0");
        String firstxdelta = r.header("x-delta");
        String body = r.body().prettyPrint();
        System.out.println("x-delta: " + firstxdelta);
        System.out.println("Body:    " + body);

        context.assertEquals("2", r.getBody().jsonPath().getString("users.01.size()"));
        context.assertTrue(r.getBody().jsonPath().getString("users.01.user.v1.identity").contains("01"));
        context.assertTrue(r.getBody().jsonPath().getString("users.01.login.v1.credentials").contains("number"));

        // --------------

        // add some new users
        createIdentity("02");
        createCredentials("02");

        // set the x delta to retrieved value
        r = given().get("users?expand=100&delta=" + firstxdelta);
        String secondxdelta = r.header("x-delta");
        body = r.body().prettyPrint();
        System.out.println("x-delta: " + secondxdelta);
        System.out.println("Body:    " + body);

        context.assertEquals("2", r.getBody().jsonPath().getString("users.size()"));
        context.assertEquals("2", r.getBody().jsonPath().getString("users.02.size()"));
        context.assertEquals("0", r.getBody().jsonPath().getString("users.01.login.v1.size()"));
        context.assertEquals("0", r.getBody().jsonPath().getString("users.01.user.v1.size()"));

        // --------------

        // update credentials for user 1
        createCredentials("01");

        // use delta value from first test (2)
        r = given().get("users?expand=100&delta=" + firstxdelta);
        String thirdxdelta = r.header("x-delta");
        body = r.body().prettyPrint();
        System.out.println("x-delta: " + thirdxdelta);
        System.out.println("Body:    " + body);
        context.assertEquals("2", r.getBody().jsonPath().getString("users.size()"));
        context.assertEquals("2", r.getBody().jsonPath().getString("users.02.size()"));
        context.assertTrue(r.getBody().jsonPath().getString("users.02.login.v1.credentials").contains("number"));
        context.assertTrue(r.getBody().jsonPath().getString("users.02.user.v1.identity").contains("02"));
        context.assertEquals("1", r.getBody().jsonPath().getString("users.01.login.v1.size()"));
        context.assertTrue(r.getBody().jsonPath().getString("users.01.login.v1.credentials").contains("number"));
        context.assertEquals("0", r.getBody().jsonPath().getString("users.01.user.v1.size()"));

        async.complete();
    }

    /**
     * Tests a deep expand with zip and delta
     */
    @Test
    public void testAdvancedDeltaZipExpand(TestContext context) {
        Async async = context.async();
        delete();

        // start structure
        createIdentity("01");
        createCredentials("01");

        Response r = given().get("users?expand=100&zip=true&delta=0");
        String firstxdelta = r.header("x-delta");
        System.out.println("x-delta: " + firstxdelta);
        Map<String, JsonObject> result = getZipContent(r, context);
        context.assertEquals(2, result.size());

        int assertedAll = 0;
        for (String path : result.keySet()) {
            JsonObject content = result.get(path);
            if (path.equals("users/01/user/v1/identity")) {
                context.assertEquals("01", content.getString("number"));
                assertedAll++;
            }

            if (path.equals("users/01/login/v1/credentials")) {
                context.assertEquals("01", content.getString("number"));
                assertedAll++;
            }
        }
        context.assertEquals(assertedAll, result.size());

        // --------------

        // add some new users
        createIdentity("02");
        createCredentials("02");

        // set the x delta to retrieved value
        r = given().get("users?expand=100&zip=true&delta=" + firstxdelta);
        result = getZipContent(r, context);
        context.assertEquals(2, result.size());

        assertedAll = 0;
        for (String path : result.keySet()) {
            JsonObject content = result.get(path);
            if (path.equals("users/02/user/v1/identity")) {
                context.assertEquals("02", content.getString("number"));
                assertedAll++;
            }

            if (path.equals("users/02/login/v1/credentials")) {
                context.assertEquals("02", content.getString("number"));
                assertedAll++;
            }
        }
        context.assertEquals(assertedAll, result.size());

        // --------------

        // update credentials for user 1
        createCredentials("01");

        // use delta value from first test (2)
        r = given().get("users?expand=100&zip=true&delta=" + firstxdelta);

        result = getZipContent(r, context);
        context.assertEquals(3, result.size());

        assertedAll = 0;
        for (String path : result.keySet()) {
            JsonObject content = result.get(path);
            if (path.equals("users/02/user/v1/identity")) {
                context.assertEquals("02", content.getString("number"));
                assertedAll++;
            }

            if (path.equals("users/02/login/v1/credentials")) {
                context.assertEquals("02", content.getString("number"));
                assertedAll++;
            }

            if (path.equals("users/01/login/v1/credentials")) {
                context.assertEquals("01", content.getString("number"));
                assertedAll++;
            }

        }
        context.assertEquals(assertedAll, result.size());

        async.complete();
    }

    @Test
    public void duplicatedCORSHeaderTest(TestContext context) {
        Async async = context.async();
        delete();

        with().body("{ \"foo\": \"bar\" }").put("resources/res1");
        with().body("{ \"foo\": \"bar\" }").put("resources/res2");
        with().body("{ \"foo\": \"bar\" }").put("resources/res3");

        // expand parameter
        Response response = given().param("expand", 1).header("Origin", ORIGIN_ADDRESS).when().get("resources/");
        response.then().assertThat().header("Access-Control-Allow-Origin", ORIGIN_ADDRESS);

        Assert.assertTrue("Response cannot contain more than one '"+CORS_HEADER_CREDENTIALS+"' header", response.getHeaders().getValues(CORS_HEADER_CREDENTIALS).size() == 1);
        Assert.assertTrue("Response cannot contain more than one '"+CORS_HEADER_METHODS+"' header", response.getHeaders().getValues(CORS_HEADER_METHODS).size() == 1);
        Assert.assertTrue("Response cannot contain more than one '"+CORS_HEADER_ORIGIN+"' header", response.getHeaders().getValues(CORS_HEADER_ORIGIN).size() == 1);

        // delta parameter
        response = given().param("delta", 0).header("Origin", ORIGIN_ADDRESS).when().get("resources/");
        response.then().assertThat().header("Access-Control-Allow-Origin", ORIGIN_ADDRESS);

        Assert.assertTrue("Response cannot contain more than one '"+CORS_HEADER_CREDENTIALS+"' header", response.getHeaders().getValues(CORS_HEADER_CREDENTIALS).size() == 1);
        Assert.assertTrue("Response cannot contain more than one '"+CORS_HEADER_METHODS+"' header", response.getHeaders().getValues(CORS_HEADER_METHODS).size() == 1);
        Assert.assertTrue("Response cannot contain more than one '"+CORS_HEADER_ORIGIN+"' header", response.getHeaders().getValues(CORS_HEADER_ORIGIN).size() == 1);

        async.complete();
    }

    private Map<String, JsonObject> getZipContent(Response response, TestContext context) {
        byte[] bArray = response.getBody().asByteArray();
        context.assertNotNull(bArray);

        Map<String, JsonObject> map = new HashMap<>();
        try (ByteArrayInputStream bInputStream = new ByteArrayInputStream(bArray);
                ZipInputStream inputStream = new ZipInputStream(bInputStream)) {

            byte[] buffer = new byte[2048];

            ZipEntry entry;
            while ((entry = inputStream.getNextEntry()) != null) {
                Buffer cBuffer = Buffer.buffer();

                int len = 0;
                while ((len = inputStream.read(buffer)) > 0) {
                    cBuffer.appendBytes(buffer, 0, len);
                }

                map.put(entry.getName(), new JsonObject(cBuffer.toString("UTF-8")));
            }

        } catch (Exception e) {
            context.fail("Exception: " + e.getMessage());
        }

        return map;
    }

    /**
     * /users/{personalNumber}/user/v1/identity
     * {
     * "fullName": "John Smith",
     * "unit": "IT126"
     * }
     * 
     * @param number
     */
    private void createIdentity(String number) {
        given().body("{ \"number\" : \"" + number + "\"}").header("x-delta", "auto").put("/users/" + number + "/user/v1/identity");
    }

    /**
     * /users/{personalNumber}/login/v1/credentials
     * {
     * "number": "personalNumber"
     * }
     * 
     * @param number
     */
    private void createCredentials(String number) {
        given().body("{ \"number\" : \"" + number + "\"}").header("x-delta", "auto").put("/users/" + number + "/login/v1/credentials");
    }
}
