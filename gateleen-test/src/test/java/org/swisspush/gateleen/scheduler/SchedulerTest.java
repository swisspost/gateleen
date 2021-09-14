package org.swisspush.gateleen.scheduler;

import io.restassured.RestAssured;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;

import java.util.Calendar;
import java.util.Date;

import static com.jayway.awaitility.Awaitility.await;
import static io.restassured.RestAssured.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.*;

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

        await().atMost(30, SECONDS).until(() ->
                get("/tests/" + SERVER_NAME + "/schedulerWithPayload").then().extract().body().jsonPath().getString("content"), equalTo(content));

        async.complete();
    }

    @Test
    public void testExpiredRequest(TestContext context) throws Exception {
        Async async = context.async();
        delete();

        String content = String.valueOf(System.currentTimeMillis());

        String testSchedulers = "{\n"
                + "  \"schedulers\": {\n"
                + "    \"gateleen-test\": {\n"
                + "      \"cronExpression\": \"/2 * * * * ?\",\n"
                + "      \"requests\": [\n"
                + "        {\n"
                + "          \"uri\": \"" + SERVER_ROOT + "/tests/" + SERVER_NAME + "/expiringRequest\",\n"
                + "          \"method\": \"DELETE\",\n"
                + "          \"payload\": {\"content\": \"" + content + "\"},\n"
                + "          \"headers\": [[ \"x-queue-expire-after\", \"1\"]]\n"
                + "        },\n"
                + "        {\n"
                + "          \"uri\": \"" + SERVER_ROOT + "/tests/" + SERVER_NAME + "/notExpiringRequest\",\n"
                + "          \"method\": \"PUT\",\n"
                + "          \"payload\": {\"content\": \"" + content + "\"}\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  }\n"
                + "}";

        delete("/tests/" + SERVER_NAME + "/notExpiringRequest");

        with().body(testSchedulers).put("/admin/v1/schedulers").then().assertThat().statusCode(200);

        await().atMost(30, SECONDS).until(() -> get("/tests/" + SERVER_NAME + "/notExpiringRequest").then().extract().statusCode(), equalTo(200));

        async.complete();
    }

    @Test
    public void testSchedulerWithRandomOffset(TestContext context) throws Exception {
        Async async = context.async();
        delete();

        // prepare the settings
        String content = String.valueOf(System.currentTimeMillis());
        String schedulerName = "scheduler-randomOffset-test";
        String resourceName = "schedulerWithRandomOffset";
        String cronExpression = "/15 * * * * ?";
        int maxRandomOffset = 20;

        // execution may be within a certain delay, because of the vertx timer used in the Scheduler
        int tolerance = 5;

        // scheduler config
        String testSchedulers = "{\n"
                + "  \"schedulers\": {\n"
                + "    \"" + schedulerName + "\": {\n"
                + "      \"cronExpression\": \"" + cronExpression + "\",\n"
                + "      \"randomOffset\": " + maxRandomOffset + ",\n"
                + "      \"requests\": [\n"
                + "        {\n"
                + "          \"uri\": \"" + SERVER_ROOT + "/tests/" + SERVER_NAME + "/" + resourceName + "\",\n"
                + "          \"method\": \"PUT\",\n"
                + "          \"payload\": {\"content\": \"" + content + "\"}\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  }\n"
                + "}";

        delete("/tests/" + SERVER_NAME + "/" + resourceName);
        with().body(testSchedulers).put("/admin/v1/schedulers").then().assertThat().statusCode(200);

        // wait for the scheduler to be created
        await().atMost(10, SECONDS).until( () -> {
            if ( schedulerResourceManager.getSchedulers() != null ) {
                return schedulerResourceManager.getSchedulers().stream().filter(s -> s.getName().equals(schedulerName)).findFirst().get();
            }
            return null;
        }, is(notNullValue()));

        // get the scheduler and the randomOffset
        Scheduler scheduler = schedulerResourceManager.getSchedulers().stream().filter(s -> s.getName().equals(schedulerName)).findFirst().get();
        long randomOffset = scheduler.getRandomOffset();

        // wait for the vertx timer to fire the first time
        await().atMost(10, SECONDS).until( () -> jedis.get("schedulers:" + schedulerName), is(notNullValue()));

        // RUN 1
        delete("/tests/" + SERVER_NAME + "/" + resourceName);
        Calendar nextRun = Calendar.getInstance();
        nextRun.setTime( new Date(Long.valueOf(jedis.get("schedulers:" + schedulerName))));
        await().atMost(30, SECONDS).until(() -> get("/tests/" + SERVER_NAME + "/" + resourceName).statusCode() == 200 &&
                get("/tests/" + SERVER_NAME + "/" + resourceName).getBody().jsonPath().getString("content").equals(content));
        Assert.assertTrue("successful delayed #1", successfulDelayed(nextRun,randomOffset, tolerance) );

        // RUN 2
        delete("/tests/" + SERVER_NAME + "/" + resourceName);
        nextRun.setTime( new Date(Long.valueOf(jedis.get("schedulers:" + schedulerName))));
        await().atMost(30, SECONDS).until(() -> get("/tests/" + SERVER_NAME + "/" + resourceName).statusCode() == 200 &&
                get("/tests/" + SERVER_NAME + "/" + resourceName).getBody().jsonPath().getString("content").equals(content));
        Assert.assertTrue("successful delayed #2", successfulDelayed(nextRun,randomOffset, tolerance) );


        // RUN 3
        delete("/tests/" + SERVER_NAME + "/" + resourceName);
        nextRun.setTime( new Date(Long.valueOf(jedis.get("schedulers:" + schedulerName))));
        await().atMost(30, SECONDS).until(() -> get("/tests/" + SERVER_NAME + "/" + resourceName).statusCode() == 200 &&
                get("/tests/" + SERVER_NAME + "/" + resourceName).getBody().jsonPath().getString("content").equals(content));
        Assert.assertTrue("successful delayed #3", successfulDelayed(nextRun,randomOffset, tolerance) );


        async.complete();
    }

    @Test
    public void testSchedulerWithExecuteOnStartupTrue(TestContext context) throws Exception {
        Async async = context.async();
        delete();

        // prepare the settings
        String content = String.valueOf(System.currentTimeMillis());
        String schedulerName = "scheduler-executeOnStartup-test";
        String resourceName = "schedulerWithExecuteInStartup";
        String cronExpression = "0 0 1 1 1 ?";

        // scheduler config
        String testSchedulers = "{\n"
                + "  \"schedulers\": {\n"
                + "    \"" + schedulerName + "\": {\n"
                + "      \"cronExpression\": \"" + cronExpression + "\",\n"
                + "      \"executeOnStartup\": true,\n"
                + "      \"requests\": [\n"
                + "        {\n"
                + "          \"uri\": \"" + SERVER_ROOT + "/tests/" + SERVER_NAME + "/" + resourceName + "\",\n"
                + "          \"method\": \"PUT\",\n"
                + "          \"payload\": {\"content\": \"" + content + "\"}\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  }\n"
                + "}";

        delete("/tests/" + SERVER_NAME + "/" + resourceName);
        with().body(testSchedulers).put("/admin/v1/schedulers").then().assertThat().statusCode(200);

        // wait for the scheduler to be created
        await().atMost(10, SECONDS).until( () -> {
            if ( schedulerResourceManager.getSchedulers() != null ) {
                return schedulerResourceManager.getSchedulers().stream().filter(s -> s.getName().equals(schedulerName)).findFirst().get();
            }
            return null;
        }, is(notNullValue()));

        // wait for the vertx timer to fire the first time
        await().atMost(10, SECONDS).until( () -> jedis.get("schedulers:" + schedulerName), is(notNullValue()));

        // Test if first run
        await().atMost(30, SECONDS).until(() -> get("/tests/" + SERVER_NAME + "/" +resourceName ).then().extract().statusCode(), equalTo(200));

        async.complete();
    }

    @Test
    public void testSchedulerWithExecuteOnStartupFalse(TestContext context) throws Exception {
        Async async = context.async();
        delete();

        // prepare the settings
        String content = String.valueOf(System.currentTimeMillis());
        String schedulerName = "scheduler-executeOnStartup-test";
        String resourceName = "schedulerWithExecuteInStartup";
        String cronExpression = "0 0 1 1 1 ?";

        // scheduler config
        String testSchedulers = "{\n"
                + "  \"schedulers\": {\n"
                + "    \"" + schedulerName + "\": {\n"
                + "      \"cronExpression\": \"" + cronExpression + "\",\n"
                + "      \"executeOnStartup\": false ,\n"
                + "      \"requests\": [\n"
                + "        {\n"
                + "          \"uri\": \"" + SERVER_ROOT + "/tests/" + SERVER_NAME + "/" + resourceName + "\",\n"
                + "          \"method\": \"PUT\",\n"
                + "          \"payload\": {\"content\": \"" + content + "\"}\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  }\n"
                + "}";

        delete("/tests/" + SERVER_NAME + "/" + resourceName);
        with().body(testSchedulers).put("/admin/v1/schedulers").then().assertThat().statusCode(200);

        // wait for the scheduler to be created
        await().atMost(10, SECONDS).until( () -> {
            if ( schedulerResourceManager.getSchedulers() != null ) {
                return schedulerResourceManager.getSchedulers().stream().filter(s -> s.getName().equals(schedulerName)).findFirst().get();
            }
            return null;
        }, is(notNullValue()));

        // wait for the vertx timer to fire the first time
        await().atMost(10, SECONDS).until( () -> jedis.get("schedulers:" + schedulerName), is(notNullValue()));

        // Test if first run
        await().atMost(30, SECONDS).until(() -> get("/tests/" + SERVER_NAME + "/" +resourceName ).then().extract().statusCode(), equalTo(404));

        async.complete();
    }

    private boolean successfulDelayed(final Calendar nextRun, final long randomOffset, final int tolerance) {
        long planedRun = nextRun.getTimeInMillis();

        Date now = new Date();

        // inaccurate execution time
        Calendar virtualRunTime = Calendar.getInstance();
        virtualRunTime.setTime(now);
        virtualRunTime.set(Calendar.MILLISECOND, 0);
        virtualRunTime.add(Calendar.MILLISECOND, -1 * Math.toIntExact(randomOffset) );
        long currentRun = virtualRunTime.getTimeInMillis();

        // tolerance times (add tolerance to inaccurate execution time)
        Calendar virtualRunTimeWithTolerance = Calendar.getInstance();
        virtualRunTimeWithTolerance.setTime(virtualRunTime.getTime());
        virtualRunTimeWithTolerance.add(Calendar.SECOND, -1* tolerance);
        long currentRunWithTolerance = virtualRunTimeWithTolerance.getTimeInMillis();

        boolean ok = planedRun >= currentRunWithTolerance && planedRun <= currentRun;

        System.out.println();
        System.out.println("Now:                          " + now);
        System.out.println("Virtual Runtime:              " + virtualRunTime.getTime());
        System.out.println("Virtual Runtime w. tolerance: " + virtualRunTimeWithTolerance.getTime());
        System.out.println("Planed Runtime:               " + nextRun.getTime());
        System.out.println("Offset:                       " + randomOffset );
        System.out.println("Offset worked:                " + ok);
        System.out.println();

        return ok;
    }
}
