package org.swisspush.gateleen.expansion;

import com.google.common.io.Files;
import com.jayway.restassured.response.Response;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;

import java.io.File;

import static com.jayway.restassured.RestAssured.delete;
import static com.jayway.restassured.RestAssured.given;

/**
 * Tests the ZipExtractHandler.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
@RunWith(VertxUnitRunner.class)
public class ZipExtractTest extends AbstractTest {
    private static final String TEST_RESOURCE_PATH = "ZipExtractHandlerTest/";

    @Test
    @Ignore
    public void testExtractZipContentFound(TestContext context) {
        Async async = context.async();
        init();

        // try to get gateleen.png out of the zip file
        Response response = given().get("zips/resource.zip/test/path/resources/gateleen.png");

        // resource is found?
        context.assertEquals(response.getStatusCode(), 200);

        // mime type correct?
        context.assertEquals(response.getHeader("Content-Type"), "image/png");

        // test binary file
        try {
            File responseFile = File.createTempFile("gateleen", "png");
            Files.write(response.getBody().asByteArray(), responseFile);

            File originalFile = new File(getClass().getClassLoader().getResource(TEST_RESOURCE_PATH + "gateleen.png").getFile());

            // compare if the extracted file is identical to the original file
            context.assertTrue(Files.equal(originalFile, responseFile));
        }
        catch( Exception e ) {
            context.fail(e);
        }


        // try to get test.txt out of the zip file
        response = given().get("zips/resource.zip/test/path/resources/test.txt");

        // resource is found?
        context.assertEquals(response.getStatusCode(), 200);

        // mime type correct?
        context.assertEquals(response.getHeader("Content-Type"), "text/plain");

        // test plain text
        try {
            File responseFile = File.createTempFile("test", "txt");
            Files.write(response.getBody().asByteArray(), responseFile);

            File originalFile = new File(getClass().getClassLoader().getResource(TEST_RESOURCE_PATH + "test.txt").getFile());

            // compare if the extracted file is identical to the original file
            context.assertTrue(Files.equal(originalFile, responseFile));
        }
        catch( Exception e ) {
            context.fail(e);
        }

        async.complete();
    }

    @Test
    public void testExtractZipContentNotFound(TestContext context) {
        Async async = context.async();
        init();

        // try to get notfound.png out of the zip file
        given().get("zips/resource.zip/test/path/resources/notfound.png").then().assertThat().statusCode(404);

        async.complete();
    }

    @Test
    public void testExtractZipResourceNotFound(TestContext context) {
        Async async = context.async();
        init();
        given().get("zips/notfound.zip/test/path/resource/gateleen.png").then().assertThat().statusCode(404);
        async.complete();
    }

    public void init() {
        delete();

        // prepare the test zip
        File zip = new File(getClass().getClassLoader().getResource(TEST_RESOURCE_PATH + "resource.zip").getFile());
        given().body(zip).header("Content-Type", "application/octet-stream").put("zips/resource.zip").then().assertThat().statusCode(200);
    }
}
