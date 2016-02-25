package org.swisspush.gateleen.scheduler;

import org.swisspush.gateleen.AbstractTest;
import com.jayway.restassured.RestAssured;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.restassured.RestAssured.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.equalTo;

/**
 * Created by bovetl on 25.09.2014.
 */
@RunWith(VertxUnitRunner.class)
public class SchedulerTest extends AbstractTest {

    /**
     * Overwrite RestAssured configuration
     */
    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(SERVER_ROOT);
    }

    @Test
    public void testSimpleScheduler(TestContext context) throws Exception {
        Async async = context.async();
        delete();

        String testSchedulers = "{" +
                "  \"schedulers\": {" +
                "    \"gateleen-test\": {" +
                "      \"cronExpression\": \"/8 * * * * ?\"," +
                "      \"requests\": [" +
                "        {" +
                "          \"uri\": \"" + SERVER_ROOT + "/tests/" + SERVER_NAME + "/scheduler\"," +
                "          \"method\": \"PUT\"" +
                "        }" +
                "      ]" +
                "    }" +
                "  }" +
                "}";

        delete("/tests/" + SERVER_NAME + "/scheduler");

        with().body(testSchedulers).put("/admin/v1/schedulers").then().assertThat().statusCode(200);

        await().atMost(30, SECONDS).until(() -> get("/tests/" + SERVER_NAME + "/scheduler").statusCode(), equalTo(200));

        async.complete();
    }

    @Test
    public void testSchedulerWithPayload(TestContext context) throws Exception {
        Async async = context.async();
        delete();

        String content = String.valueOf(System.currentTimeMillis());

        String testSchedulers = "{\n"
                + "  \"schedulers\": {\n"
                + "    \"gateleen-test\": {\n"
                + "      \"cronExpression\": \"/8 * * * * ?\",\n"
                + "      \"requests\": [\n"
                + "        {\n"
                + "          \"uri\": \"" + SERVER_ROOT + "/tests/" + SERVER_NAME + "/schedulerWithPayload\",\n"
                + "          \"method\": \"PUT\",\n"
                + "          \"payload\": {\"content\": \"" + content + "\"}\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  }\n"
                + "}";

        delete("/tests/" + SERVER_NAME + "/schedulerWithPayload");

        with().body(testSchedulers).put("/admin/v1/schedulers").then().assertThat().statusCode(200);

        await().atMost(30, SECONDS).until(() -> get("/tests/" + SERVER_NAME + "/schedulerWithPayload").then().extract().statusCode(), equalTo(200));

        await().atMost(30, SECONDS).until(() -> get("/tests/" + SERVER_NAME + "/schedulerWithPayload").then().extract().body().jsonPath().getString("content"), equalTo(content));

        async.complete();
    }
}
