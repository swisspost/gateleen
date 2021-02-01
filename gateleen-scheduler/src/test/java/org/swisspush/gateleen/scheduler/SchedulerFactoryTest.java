package org.swisspush.gateleen.scheduler;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.RedisClient;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.Collections;
import java.util.List;

/**
 * Tests for the {@link SchedulerFactory} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class SchedulerFactoryTest {

    private Vertx vertx;
    private MonitoringHandler monitoringHandler;
    private RedisClient redisClient;
    private SchedulerFactory schedulerFactory;

    @Rule
    public ExpectedException thrown= ExpectedException.none();

    private String schedulersSchema = ResourcesUtils.loadResource("gateleen_scheduler_schema_schedulers", true);

    private final String VALID_SCHEDULER_RESOURCE = ResourcesUtils.loadResource("testresource_valid_scheduler_resource", true);
    private final String MISSING_SCHEDULERS_PROPERTY = ResourcesUtils.loadResource("testresource_missing_schedulers_property", true);
    private final String MISSING_CRONEXPRESSION_PROPERTY = ResourcesUtils.loadResource("testresource_missing_cronexpression_property", true);
    private final String INVALID_JSON = ResourcesUtils.loadResource("testresource_invalid_json", true);

    @Before
    public void setUp(){
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
        redisClient = Mockito.mock(RedisClient.class);
        monitoringHandler = Mockito.mock(MonitoringHandler.class);

        schedulerFactory = new SchedulerFactory(null, Collections.emptyMap(), vertx, redisClient, monitoringHandler, schedulersSchema, Address.redisquesAddress());
    }

    @Test
    public void testInvalidJson() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Unable to parse json");
        schedulerFactory.parseSchedulers(Buffer.buffer(INVALID_JSON));
    }

    @Test
    public void testValidSchedulerConfig(TestContext context) throws ValidationException {
        List<Scheduler> schedulers = schedulerFactory.parseSchedulers(Buffer.buffer(VALID_SCHEDULER_RESOURCE));
        context.assertNotNull(schedulers);
        context.assertEquals(3, schedulers.size());

        // scheduler without payload and headers
        List<HttpRequest> requestsScheduler1 = schedulers.get(0).requests();
        context.assertEquals(1, requestsScheduler1.size());
        context.assertEquals(0, requestsScheduler1.get(0).getHeaders().size());

        // scheduler with payload and headers
        List<HttpRequest> requestsScheduler2 = schedulers.get(1).requests();
        context.assertEquals(1, requestsScheduler2.size());
        context.assertEquals(2, requestsScheduler2.get(0).getHeaders().size());
        context.assertEquals("bar", requestsScheduler2.get(0).getHeaders().get("x-foo"));
        context.assertTrue(requestsScheduler2.get(0).getHeaders().contains("content-length"));

        // scheduler with headers but no payload
        List<HttpRequest> requestsScheduler3 = schedulers.get(2).requests();
        context.assertEquals(1, requestsScheduler3.size());
        context.assertEquals(1, requestsScheduler3.get(0).getHeaders().size());
        context.assertEquals("bar", requestsScheduler3.get(0).getHeaders().get("x-foo"));
    }

    @Test
    public void testValidSchedulerConfigWithDefaultHeaders(TestContext context) throws ValidationException {
        schedulerFactory = new SchedulerFactory(null, Collections.singletonMap("x-foo", "zzz"), vertx, redisClient,
                monitoringHandler, schedulersSchema, Address.redisquesAddress());
        List<Scheduler> schedulers = schedulerFactory.parseSchedulers(Buffer.buffer(VALID_SCHEDULER_RESOURCE));
        context.assertNotNull(schedulers);
        context.assertEquals(3, schedulers.size());

        // scheduler without payload and headers
        List<HttpRequest> requestsScheduler1 = schedulers.get(0).requests();
        context.assertEquals(1, requestsScheduler1.size());
        context.assertEquals(1, requestsScheduler1.get(0).getHeaders().size());
        context.assertEquals("zzz", requestsScheduler1.get(0).getHeaders().get("x-foo"));

        // scheduler with payload and headers
        List<HttpRequest> requestsScheduler2 = schedulers.get(1).requests();
        context.assertEquals(1, requestsScheduler2.size());
        context.assertEquals(2, requestsScheduler2.get(0).getHeaders().size());

        // default header x-foo : zzz should be overridden by the scheduler config
        context.assertEquals("bar", requestsScheduler2.get(0).getHeaders().get("x-foo"));
        context.assertTrue(requestsScheduler2.get(0).getHeaders().contains("content-length"));

        // scheduler with headers but no payload
        List<HttpRequest> requestsScheduler3 = schedulers.get(2).requests();
        context.assertEquals(1, requestsScheduler3.size());
        context.assertEquals(1, requestsScheduler3.get(0).getHeaders().size());

        // default header x-foo : zzz should be overridden by the scheduler config
        context.assertEquals("bar", requestsScheduler3.get(0).getHeaders().get("x-foo"));
    }

    @Test
    public void testMissingSchedulersProperty(TestContext context) throws ValidationException {
        try {
            schedulerFactory.parseSchedulers(Buffer.buffer(MISSING_SCHEDULERS_PROPERTY));
            context.fail("Should have thrown a ValidationException since 'schedulers' property is missing");
        } catch(ValidationException ex){
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(2, ex.getValidationDetails().size());
            for(Object obj : ex.getValidationDetails()){
                JsonObject jsonObject = (JsonObject) obj;
                if("additionalProperties".equalsIgnoreCase(jsonObject.getString("keyword"))){
                    context.assertEquals("listofschedulers", jsonObject.getJsonArray("unwanted").getString(0));
                } else if("required".equalsIgnoreCase(jsonObject.getString("keyword"))){
                    context.assertEquals("schedulers", jsonObject.getJsonArray("missing").getString(0));
                }
            }
        }
    }

    @Test
    public void testMissingCronExpressionProperty(TestContext context) throws ValidationException {
        try {
            schedulerFactory.parseSchedulers(Buffer.buffer(MISSING_CRONEXPRESSION_PROPERTY));
            context.fail("Should have thrown a ValidationException since 'cronExpression' property is missing");
        } catch(ValidationException ex){
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(2, ex.getValidationDetails().size());
            for(Object obj : ex.getValidationDetails()){
                JsonObject jsonObject = (JsonObject) obj;
                if("additionalProperties".equalsIgnoreCase(jsonObject.getString("keyword"))){
                    context.assertEquals("cron", jsonObject.getJsonArray("unwanted").getString(0));
                } else if("required".equalsIgnoreCase(jsonObject.getString("keyword"))){
                    context.assertEquals("cronExpression", jsonObject.getJsonArray("missing").getString(0));
                }
            }
        }
    }
}
