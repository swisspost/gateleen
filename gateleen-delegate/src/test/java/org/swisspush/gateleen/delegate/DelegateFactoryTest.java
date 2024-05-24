package org.swisspush.gateleen.delegate;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpClient;

import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.ClientRequestCreator;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.HashMap;
import java.util.Map;

import static org.swisspush.gateleen.core.util.ResourcesUtils.loadResource;

/**
 * Tests for the {@link DelegateFactory} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class DelegateFactoryTest {

    private static final String TYPE = "type";
    private static final String ARGUMENTS = "arguments";
    private static final String INTEGER = "integer";
    private static final String ARRAY = "array";

    private DelegateFactory delegateFactory;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private String delegatesSchema = loadResource("gateleen_delegate_schema_delegates", true);

    private final String INVALID_JSON = loadResource("invalid_json", true);
    private final String VALID_DELEGATE = loadResource("valid_delegate", true);
    private final String VALID_DYNAMIC_HEADERS_DELEGATE = loadResource("valid_dynamic_headers_delegate", true);
    private final String INVALID_PATTERN_DELEGATE = loadResource("invalid_pattern_delegate", true);
    private final String INVALID_HEADERS_DYNAMICHEADERS_MIXED_DELEGATE = loadResource("invalid_headers_dynamicHeaders_mixed_delegate", true);
    private final String MISSING_PROPERTY_DELEGATE = loadResource("missing_property_delegate", true);
    private final String UNWANTED_PROPERTY_DELEGATE = loadResource("unwanted_property_delegate", true);
    private final String PAYLOAD_TRANSFORM_PROPERTY_DELEGATE = loadResource("payload_and_transform_property_delegate", true);
    private final String TRANSFORM_TRANSFORM_WITH_METADATA_PROPERTY_DELEGATE = loadResource("transform_and_transform_with_metadata_property_delegate", true);
    private final String TRANSFORM_PROPERTY_NOT_OBJECT_DELEGATE = loadResource("transform_property_not_object_delegate", true);
    private final String TRANSFORM_WITH_METADATA_PROPERTY_NOT_OBJECT_DELEGATE = loadResource("transform_with_metadata_property_not_object_delegate", true);
    private final String VALID_TRANSFORM_PROPERTY_DELEGATE = loadResource("valid_transform_delegate", true);
    private final String VALID_TRANSFORM_WITH_METADATA_PROPERTY_DELEGATE = loadResource("valid_transform_with_metadata_delegate", true);
    private final String INVALID_TRANSFORM_PROPERTY_DELEGATE = loadResource("invalid_transform_delegate", true);
    private final String INVALID_TRANSFORM_WITH_METADATA_PROPERTY_DELEGATE = loadResource("invalid_transform_with_metadata_delegate", true);

    @Before
    public void setUp() {
        Vertx vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
        Map<String, Object> properties = new HashMap<>();

        delegateFactory = new DelegateFactory(new ClientRequestCreator(Mockito.mock(HttpClient.class)), properties, delegatesSchema, null);
    }

    @Test
    public void testInvalidJson() throws ValidationException {
        thrown.expect(ValidationException.class);
        thrown.expectMessage("Unable to parse json");
        delegateFactory.parseDelegate("someDelegate", Buffer.buffer(INVALID_JSON));
    }

    @Test
    public void testInvalidPatternJson() throws ValidationException {
        thrown.expect(ValidationException.class);
        thrown.expectMessage("Could not parse pattern [.*/([^/]+.*]");
        delegateFactory.parseDelegate("someDelegate", Buffer.buffer(INVALID_PATTERN_DELEGATE));
    }

    @Test
    public void testInvalidWithHeadersAndDynamicHeaders(TestContext context) {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(INVALID_HEADERS_DYNAMICHEADERS_MIXED_DELEGATE));
            context.fail("Should have thrown a ValidationException since 'headers' and 'dynamicHeaders' properties are " +
                    "not allowed concurrently");
        } catch (ValidationException ex) {
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(2, ex.getValidationDetails().size());
            context.assertEquals("oneOf", ex.getValidationDetails().getJsonObject(0).getString(TYPE));
            context.assertEquals("not", ex.getValidationDetails().getJsonObject(1).getString(TYPE));
        }
    }

    @Test
    public void testValidDelegateConfig(TestContext context) throws ValidationException {
        Delegate delegate = delegateFactory.parseDelegate("someDelegate", Buffer.buffer(VALID_DELEGATE));
        context.assertNotNull(delegate);
        context.assertEquals("someDelegate", delegate.getName());
        context.assertEquals(HeaderFunctions.DO_NOTHING, delegate.getDelegateRequests().get(0).getHeaderFunction());
    }

    @Test
    public void testValidDynamicHeadersDelegateConfig(TestContext context) throws ValidationException {
        Delegate delegate = delegateFactory.parseDelegate("someDelegate",
                Buffer.buffer(VALID_DYNAMIC_HEADERS_DELEGATE));
        context.assertNotNull(delegate);
        context.assertEquals("someDelegate", delegate.getName());
        context.assertEquals(1, delegate.getDelegateRequests().size());
        context.assertNotEquals(HeaderFunctions.DO_NOTHING, delegate.getDelegateRequests().get(0).getHeaderFunction());

        HeadersMultiMap inputHeaders = new HeadersMultiMap();
        inputHeaders.add("x-bar", "hello");
        inputHeaders.add("x-dummy", "world");
        inputHeaders.add("x-remove_me", "remove_me");

        delegate.getDelegateRequests().get(0).getHeaderFunction().apply(inputHeaders);

        context.assertEquals("hello", inputHeaders.get("x-foo")); // added by the header function using the value from x-bar
        context.assertEquals("hello", inputHeaders.get("x-bar"));
        context.assertEquals("world", inputHeaders.get("x-dummy"));
        context.assertFalse(inputHeaders.contains("x-remove_me"));
    }

    @Test
    public void testMissingProperty(TestContext context) {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(MISSING_PROPERTY_DELEGATE));
            context.fail("Should have thrown a ValidationException since 'pattern' property is missing");
        } catch (ValidationException ex) {
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for (Object obj : ex.getValidationDetails()) {
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals("required", jsonObject.getString(TYPE));
                context.assertEquals("pattern", jsonObject.getJsonArray(ARGUMENTS).getString(0));
            }
        }
    }

    @Test
    public void testUnwantedAdditionalProperty(TestContext context) {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(UNWANTED_PROPERTY_DELEGATE));
            context.fail("Should have thrown a ValidationException since additional 'foo' property is not allowed");
        } catch (ValidationException ex) {
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for (Object obj : ex.getValidationDetails()) {
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals("additionalProperties", jsonObject.getString(TYPE));
                context.assertEquals("foo", jsonObject.getJsonArray(ARGUMENTS).getString(0));
            }
        }
    }

    @Test
    public void testPayloadAndTransformPropertyConcurrently(TestContext context) {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(PAYLOAD_TRANSFORM_PROPERTY_DELEGATE));
            context.fail("Should have thrown a ValidationException since 'payload' and 'transform' properties are " +
                    "not allowed concurrently");
        } catch (ValidationException ex) {
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for (Object obj : ex.getValidationDetails()) {
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals("oneOf", jsonObject.getString(TYPE));
            }
        }
    }

    @Test
    public void testTransformPropertyNotObject(TestContext context) {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(TRANSFORM_PROPERTY_NOT_OBJECT_DELEGATE));
            context.fail("Should have thrown a ValidationException since 'transform' property must be an object");
        } catch (ValidationException ex) {
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for (Object obj : ex.getValidationDetails()) {
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals(TYPE, jsonObject.getString(TYPE));
                context.assertEquals(INTEGER, jsonObject.getJsonArray(ARGUMENTS).getString(0));
                context.assertEquals(ARRAY, jsonObject.getJsonArray(ARGUMENTS).getString(1));
            }
        }
    }

    @Test
    public void testInvalidTransformDelegateConfig(TestContext context) {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(INVALID_TRANSFORM_PROPERTY_DELEGATE));
            context.fail("Should have thrown a ValidationException since 'transform' spec is not valid");
        } catch (ValidationException ex) {
            context.assertEquals("Could not parse json transform specification of delegate someDelegate", ex.getMessage());
        }
    }

    @Test
    public void testValidTransformDelegateConfig(TestContext context) throws ValidationException {
        Delegate delegate = delegateFactory.parseDelegate("someDelegate",
                Buffer.buffer(VALID_TRANSFORM_PROPERTY_DELEGATE));
        context.assertNotNull(delegate);
        context.assertEquals("someDelegate", delegate.getName());
    }

    @Test
    public void testTransformWithMetadataPropertyNotObject(TestContext context) {
        try {
            delegateFactory.parseDelegate("someDelegate",
                    Buffer.buffer(TRANSFORM_WITH_METADATA_PROPERTY_NOT_OBJECT_DELEGATE));
            context.fail("Should have thrown a ValidationException since 'transformWithMetadata' property must be an object");
        } catch (ValidationException ex) {
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for (Object obj : ex.getValidationDetails()) {
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals(TYPE, jsonObject.getString(TYPE));
                context.assertEquals(INTEGER, jsonObject.getJsonArray(ARGUMENTS).getString(0));
                context.assertEquals(ARRAY, jsonObject.getJsonArray(ARGUMENTS).getString(1));
            }
        }
    }

    @Test
    public void testInvalidTransformWithMetadataDelegateConfig(TestContext context) {
        try {
            delegateFactory.parseDelegate("someDelegate", Buffer.buffer(INVALID_TRANSFORM_WITH_METADATA_PROPERTY_DELEGATE));
            context.fail("Should have thrown a ValidationException since 'transformWithMetadata' spec is not valid");
        } catch (ValidationException ex) {
            context.assertEquals("Could not parse json transformWithMetadata specification of delegate someDelegate",
                    ex.getMessage());
        }
    }

    @Test
    public void testTransformAndTransformWithMetadataPropertyConcurrently(TestContext context) {
        try {
            delegateFactory.parseDelegate("someDelegate",
                    Buffer.buffer(TRANSFORM_TRANSFORM_WITH_METADATA_PROPERTY_DELEGATE));
            context.fail("Should have thrown a ValidationException since 'transform' and 'transformWithMetadata' " +
                    "properties are not allowed concurrently");
        } catch (ValidationException ex) {
            context.assertNotNull(ex.getValidationDetails());
            context.assertEquals(1, ex.getValidationDetails().size());
            for (Object obj : ex.getValidationDetails()) {
                JsonObject jsonObject = (JsonObject) obj;
                context.assertEquals("oneOf", jsonObject.getString("type"));
            }
        }
    }

    @Test
    public void testValidTransformWithMetadataDelegateConfig(TestContext context) throws ValidationException {
        Delegate delegate = delegateFactory.parseDelegate("someDelegate",
                Buffer.buffer(VALID_TRANSFORM_WITH_METADATA_PROPERTY_DELEGATE));
        context.assertNotNull(delegate);
        context.assertEquals("someDelegate", delegate.getName());
    }
}
