package org.swisspush.gateleen.expansion;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import io.restassured.response.Response;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.awaitility.Awaitility.await;
import static io.restassured.RestAssured.*;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.hamcrest.CoreMatchers.*;

/**
 * Test class for the expand and zip feature of
 * gateleen. <br />
 * Base tests for the expand feature can be found
 * in crush-test -> ExpandDeltaTest. <br />
 * This test covers only the advanced features
 * of expand and zip.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class ExpandZipTest extends AbstractTest {
    /**
     * Tests whether the parameter switch zip=true/false
     * works properly.
     */
    @Test
    public void testExpandZipParameterSwitch(TestContext context) {
        Async async = context.async();
        init();

        given().param("expand", 1).when().get("tree/").then().assertThat().contentType(containsString("application/json"));
        given().param("expand", 1).param("zip", "false").when().get("tree/").then().assertThat().contentType(containsString("application/json"));
        given().param("expand", 1).param("zip", "true").when().get("tree/").then().assertThat().contentType(containsString("application/octet-stream"));
        given().param("expand", 1).param("zip", "store").when().get("tree/").then().assertThat().contentType(containsString("application/octet-stream"));

        async.complete();
    }

    /**
     * Tests whether the zip (uncompressed) feature works
     * properly.
     */
    @Test
    public void testUncompressedZip(TestContext context) {
        Async async = context.async();
        init();
        testZip(context, "store");
        async.complete();
    }

    /**
     * Tests whether the zip (compressed) feature works
     * properly.
     */
    @Test
    public void testCompressedZip(TestContext context) {
        Async async = context.async();
        init();
        testZip(context, "true");
        async.complete();
    }

    public void testZip(TestContext context, String type) {
        Response response = given().param("expand", 100).param("zip", type).when().get("tree/");
        response.then().assertThat().statusCode(200);
        byte[] bArray = response.getBody().asByteArray();

        context.assertNotNull(bArray);

        try (ByteArrayInputStream bInputStream = new ByteArrayInputStream(bArray);
                ZipInputStream inputStream = new ZipInputStream(bInputStream)) {

            int entriesFound = 0;

            byte[] buffer = new byte[2048];

            ZipEntry entry;
            while ((entry = inputStream.getNextEntry()) != null) {
                System.out.println(entry.getName());

                Buffer cBuffer = Buffer.buffer();

                int len = 0;
                while ((len = inputStream.read(buffer)) > 0) {
                    cBuffer.appendBytes(buffer, 0, len);
                }

                /*
                 * If a request made by the name (path),
                 * leads to the same json object,
                 * everything is fine.
                 */

                JsonObject actualContent = new JsonObject(cBuffer.toString("UTF-8"));
                JsonObject expectedContent = new JsonObject(given().get(entry.getName()).then().extract().asString());

                context.assertEquals(expectedContent, actualContent);

                entriesFound++;
            }

            context.assertEquals(11, entriesFound, "Zip items expected");

        } catch (Exception e) {
            context.fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Tests the response for requests
     * with different expand levels.
     */
    @Test
    public void testDifferentExpandLevels(TestContext context) {
        Async async = context.async();
        init();

        // return level 1
        given().param("expand", 1).when().get("tree/").then().assertThat().body(containsString("col1")).and().assertThat().body(containsString("col2")).and().assertThat().body(containsString("col3")).and().assertThat().body(containsString("res1")).and().assertThat().body(containsString("res2")).and().assertThat().body(containsString("res3")).and().assertThat().body(containsString("res4")).and()
                .assertThat().body(not(containsString("col4"))).and().assertThat().body(not(containsString("subres1")));

        // return level 2
        given().param("expand", 2).when().get("tree/").then().assertThat().body(containsString("col1")).and().assertThat().body(containsString("res1")).and().assertThat().body(containsString("bar1")).and().assertThat().body(containsString("col4")).and().assertThat().body(containsString("subres1")).and().assertThat().body(not(containsString("subres5")));

        // return level 3
        given().param("expand", 3).when().get("tree/").then().assertThat().body(containsString("res2")).and().assertThat().body(containsString("bar2")).and().assertThat().body(containsString("col4")).and().assertThat().body(containsString("subres5")).and().assertThat().body(containsString("subres1")).and().assertThat().body(containsString("bar5")).and().assertThat().body(containsString("col3"))
                .and().assertThat().body(containsString("subres4")).and().assertThat().body(containsString("bar8")).and().assertThat().body(not(containsString("bar11")));

        // return level 4
        given().param("expand", 4).when().get("tree/").then().assertThat().body(containsString("subres5")).and().assertThat().body(containsString("bar9")).and().assertThat().body(containsString("subres1")).and().assertThat().body(containsString("bar5")).and().assertThat().body(containsString("subres3")).and().assertThat().body(containsString("bar7")).and().assertThat().body(containsString("res1"))
                .and().assertThat().body(containsString("bar1"));

        async.complete();
    }

    /**
     * Tests the behaviour of the expand feature,
     * if non-json data is found.
     */
    @Test
    public void testNoJsonDataFoundExpand(TestContext context) {
        Async async = context.async();
        init();

        // put a malformed resource
        with().body("{ foo\": \"bar1\" }").put("malformed");

        // try to expand
        given().param("expand", 10).when().get("/").then().assertThat().statusCode(500).and().assertThat().body(containsString("Errors found in resources")).and().assertThat().body(containsString("malformed"));

        async.complete();
    }

    /**
     * Creates a fake tree with some resources
     */
    private void init() {
        delete();

        // add a routing
        JsonObject rules = TestUtils.addRoutingRuleMainStorage(new JsonObject());
        TestUtils.putRoutingRules(rules);

        // 4 resources in root (expandzip)
        given().body("{ \"foo\": \"bar1\" }").put("tree/resources/res1").then().assertThat().statusCode(200);
        given().body("{ \"foo\": \"bar2\" }").put("tree/resources/res2").then().assertThat().statusCode(200);
        given().body("{ \"foo\": \"bar3\" }").put("tree/resources/res3").then().assertThat().statusCode(200);
        given().body("{ \"foo\": \"bar4\" }").put("tree/resources/res4").then().assertThat().statusCode(200);

        // 2 collections in root (resources), with 2 resource each
        given().body("{ \"foo\": \"bar5\" }").put("tree/resources/col1/subres1").then().assertThat().statusCode(200);
        given().body("{ \"foo\": \"bar6\" }").put("tree/resources/col1/subres2").then().assertThat().statusCode(200);
        given().body("{ \"foo\": \"bar7\" }").put("tree/resources/col2/subres3").then().assertThat().statusCode(200);
        given().body("{ \"foo\": \"bar8\" }").put("tree/resources/col3/subres4").then().assertThat().statusCode(200);

        // 1 collection in col1, with 3 resources
        given().body("{ \"foo\": \"bar9\" }").put("tree/resources/col1/col4/subres5").then().assertThat().statusCode(200);
        given().body("{ \"foo\": \"bar10\" }").put("tree/resources/col1/col4/subres6").then().assertThat().statusCode(200);
        given().body("{ \"foo\": \"bar11\" }").put("tree/resources/col1/col4/subres7").then().assertThat().statusCode(200);

        checkGETStatusCodeWithAwait("tree/resources/res1", 200);
        checkGETStatusCodeWithAwait("tree/resources/res2", 200);
        checkGETStatusCodeWithAwait("tree/resources/res3", 200);
        checkGETStatusCodeWithAwait("tree/resources/res4", 200);

        checkGETStatusCodeWithAwait("tree/resources/col1/subres1", 200);
        checkGETStatusCodeWithAwait("tree/resources/col1/subres2", 200);
        checkGETStatusCodeWithAwait("tree/resources/col2/subres3", 200);
        checkGETStatusCodeWithAwait("tree/resources/col3/subres4", 200);

        checkGETStatusCodeWithAwait("tree/resources/col1/col4/subres5", 200);
        checkGETStatusCodeWithAwait("tree/resources/col1/col4/subres6", 200);
        checkGETStatusCodeWithAwait("tree/resources/col1/col4/subres7", 200);
    }

    /**
     * Checks if the GET request for the
     * resource gets a response with
     * the given status code.
     * 
     * @param request
     * @param statusCode
     */
    private void checkGETStatusCodeWithAwait(final String request, final Integer statusCode) {
        await().atMost(FIVE_SECONDS).until(() -> String.valueOf(when().get(request).getStatusCode()), equalTo(String.valueOf(statusCode)));
    }
}
