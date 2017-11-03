package org.swisspush.gateleen.delegate;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for the {@link DelegateFactory} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class DelegateFactoryTest {

    private Vertx vertx;
    private MonitoringHandler monitoringHandler;
    private DelegateFactory delegateFactory;
    private Map<String, Object> properties;

    @Rule
    public ExpectedException thrown= ExpectedException.none();

    private String delegatesSchema = ResourcesUtils.loadResource("gateleen_delegate_schema_delegates", true);

    private final String INVALID_JSON = ResourcesUtils.loadResource("testresource_invalid_json", true);
    private final String VALID_DELEGATE_RESOURCE = ResourcesUtils.loadResource("testresource_valid_delegate_resource", true);
    private final String MISSING_PROPERTY_DELEGATE_RESOURCE = ResourcesUtils.loadResource("testresource_missing_property_delegate_resource", true);
    private final String UNWANTED_PROPERTY_DELEGATE_RESOURCE = ResourcesUtils.loadResource("testresource_unwanted_property_delegate_resource", true);
    private final String PAYLOAD_TRANSFORM_PROPERTY_DELEGATE_RESOURCE = ResourcesUtils.loadResource("testresource_payload_and_transform_property_delegate_resource", true);
    private final String TRANSFORM_PROPERTY_NOT_OBJECT_DELEGATE_RESOURCE = ResourcesUtils.loadResource("testresource_transform_property_not_object_delegate_resource", true);
    private final String VALID_TRANSFORM_PROPERTY_DELEGATE_RESOURCE = ResourcesUtils.loadResource("testresource_valid_transform_delegate_resource", true);

    @Before
    public void setUp(){
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
        monitoringHandler = Mockito.mock(MonitoringHandler.class);
        properties = new HashMap<>();

        delegateFactory = new DelegateFactory(monitoringHandler, null, properties, delegatesSchema);
    }

    @Test
    public void testInvalidJson() throws ValidationException {
        thrown.expect( ValidationException.class );
        thrown.expectMessage("Unable to parse json");
        delegateFactory.parseDelegate("someDelegate", Buffer.buffer(INVALID_JSON));
    }

    @Test
    public void testValidDelegateConfig(TestContext context) throws ValidationException {
        Delegate delegate = delegateFactory.parseDelegate("someDelegate", Buffer.buffer(VALID_DELEGATE_RESOURCE));
        context.assertNotNull(delegate);
        context.assertEquals("someDelegate", delegate.getName());
    }

    @Test
    public void testMissingProperty(TestContext context) throws ValidationException {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(MISSING_PROPERTY_DELEGATE_RESOURCE));
            context.fail("Should have thrown a ValidationException since 'pattern' property is missing");
        } catch(ValidationException ex){
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for(Object obj : ex.getValidationDetails()){
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals("pattern", jsonObject.getJsonArray("missing").getString(0));
            }
        }
    }

    @Test
    public void testUnwantedAdditionalProperty(TestContext context) throws ValidationException {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(UNWANTED_PROPERTY_DELEGATE_RESOURCE));
            context.fail("Should have thrown a ValidationException since additional 'foo' property is not allowed");
        } catch(ValidationException ex){
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for(Object obj : ex.getValidationDetails()){
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals("foo", jsonObject.getJsonArray("unwanted").getString(0));
            }
        }
    }

    @Test
    public void testPayloadAndTransformPropertyConcurrently(TestContext context) throws ValidationException {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(PAYLOAD_TRANSFORM_PROPERTY_DELEGATE_RESOURCE));
            context.fail("Should have thrown a ValidationException since 'payload' and 'transform' properties are not allowed concurrently");
        } catch(ValidationException ex){
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for(Object obj : ex.getValidationDetails()){
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals("oneOf", jsonObject.getString("keyword"));
            }
        }
    }

    @Test
    public void testTransformPropertyNotObject(TestContext context) throws ValidationException {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(TRANSFORM_PROPERTY_NOT_OBJECT_DELEGATE_RESOURCE));
            context.fail("Should have thrown a ValidationException since 'transform' property must be an object");
        } catch(ValidationException ex){
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for(Object obj : ex.getValidationDetails()){
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals("integer", jsonObject.getString("found"));
                context.assertEquals("array", jsonObject.getJsonArray("expected").getString(0));
            }
        }
    }

    @Test
    public void testValidTransformDelegateConfig(TestContext context) throws ValidationException {
        Delegate delegate = delegateFactory.parseDelegate("someDelegate", Buffer.buffer(VALID_TRANSFORM_PROPERTY_DELEGATE_RESOURCE));
        context.assertNotNull(delegate);
        context.assertEquals("someDelegate", delegate.getName());
    }
}
