package org.swisspush.gateleen.validation;

import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.validation.mocks.HttpServerRequestMock;
import org.swisspush.gateleen.validation.mocks.HttpServerResponseMock;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import com.google.common.collect.ImmutableMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/**
 * Test class for the ValidationResourceManager
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ValidationResourceManagerTest {

    private Vertx vertx;
    private ValidationResourceManager validationResourceManager;

    private final String VALIDATION_URI = "/gateleen/server/validation";

    private final String VALIDATION_RESOURCE_VALID = "{\n" +
            "  \"resources\": [\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/resources/someResource\",\n" +
            "      \"method\": \"GET|PUT\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private final String VALIDATION_RESOURCE_VALID_SCHEMA_LOCATION = "{\n" +
            "  \"resources\": [{\n" +
            "    \"url\": \"/gateleen/resources/someResource\",\n" +
            "    \"schemaLocation\": \"/gateleen/path/to/the/schema\"\n" +
            "  }]\n" +
            "}";

    private final String VALIDATION_RESOURCE_INVALID = "{\n" +
            "  \"wrongProperty\": [\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/resources/someResource\",\n" +
            "      \"method\": \"GET|PUT\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private final String VALIDATION_RESOURCE_INVALID_2 = "{\n" +
            "  \"resources\": [\n" +
            "    {\n" +
            "      \"notAllowedProperty\": \"/gateleen/resources/someResource\",\n" +
            "      \"method\": \"GET|PUT\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private final String VALIDATION_RESOURCE_VALID_3 = "{\n"
            + "  \"resources\": [\n"
            + "    {\n"
            + "      \"url\": \"/gateleen/resources/someResource\"\n"
            + "    }\n"
            + "  ]\n"
            + "}";

    private final String VALIDATION_RESOURCE_UPDATE = "{\n" +
            "  \"resources\": [\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/resources/someResource\",\n" +
            "      \"method\": \"GET|PUT\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/resources/someOtherResource\",\n" +
            "      \"method\": \"GET|PUT\"\n" +
            "    }    \n" +
            "  ]\n" +
            "}";

    private final String VALIDATION_RESOURCE_UPDATE_INVALID = "{\n" +
            "  \"resources\": [\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/resources/someResource\",\n" +
            "      \"method\": \"GET|PUT\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"notURL\": \"/gateleen/resources/someOtherResource\",\n" +
            "      \"method\": \"GET|PUT\"\n" +
            "    }    \n" +
            "  ]\n" +
            "}";

    @Before
    public void setUp(){
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
    }

    @Test
    public void testInitWithValidResource(TestContext context){
        validationResourceManager = new ValidationResourceManager(vertx, new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_VALID)), VALIDATION_URI);
        ValidationResource res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "Creating ValidationResourceManager with a valid Resource should not result in an empty resources list");

        validationResourceManager = new ValidationResourceManager(vertx, new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_VALID_SCHEMA_LOCATION)), VALIDATION_URI);
        res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "Creating ValidationResourceManager with a valid Resource should not result in an empty resources list");
    }

    @Test
    public void testInitWithInvalidResource(TestContext context){
        validationResourceManager = new ValidationResourceManager(vertx, new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_INVALID)), VALIDATION_URI);
        ValidationResource res = validationResourceManager.getValidationResource();
        context.assertTrue(res.getResources().isEmpty(), "Creating ValidationResourceManager with an invalid Resource should result in an empty resources list");


        validationResourceManager = new ValidationResourceManager(vertx, new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_INVALID_2)), VALIDATION_URI);
        res = validationResourceManager.getValidationResource();
        context.assertTrue(res.getResources().isEmpty(), "Creating ValidationResourceManager with an invalid Resource should result in an empty resources list");

        validationResourceManager = new ValidationResourceManager(vertx, new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_VALID_3)), VALIDATION_URI);
        res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "Creating ValidationResourceManager with a Resource missing the 'methods' property should not result in an empty resources list");
    }

    @Test
    public void testHandleValidationResourceWithPUTRequest(TestContext context){
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_VALID));
        validationResourceManager = new ValidationResourceManager(vertx, storage, VALIDATION_URI);

        ValidationResource res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "Creating ValidationResourceManager with a valid Resource should not result in an empty resources list");
        context.assertEquals(1, res.getResources().size(), "The initial resource should contain 1 validation rule");

        class PUTValidationResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return VALIDATION_URI;
            }

            @Override public String path() {
                return VALIDATION_URI;
            }
        }

        PUTValidationResourceRequest request = new PUTValidationResourceRequest();
        request.setBodyContent(VALIDATION_RESOURCE_UPDATE);

        boolean handled = validationResourceManager.handleValidationResource(request);
        context.assertTrue(handled, "PUT Request to validation resource should be handled");

        res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "After the update, the resources should not be empty");
        context.assertEquals(2, res.getResources().size(), "After update, there should be 2 validation rules");

        /* check storage content */
        storage.get(VALIDATION_URI, buffer -> {
            String buffAsString = buffer.toString("UTF-8");
            context.assertEquals(VALIDATION_RESOURCE_UPDATE, buffAsString, "Updated validation resource from storage should be equal to the resource sent with the request");
            async.complete();
        });
    }

    @Test
    public void testHandleValidationResourceWithInvalidPUTRequest(TestContext context){
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_VALID));
        validationResourceManager = new ValidationResourceManager(vertx, storage, VALIDATION_URI);

        ValidationResource res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "Creating ValidationResourceManager with a valid Resource should not result in an empty resources list");
        context.assertEquals(1, res.getResources().size(), "The initial resource should contain 1 validation rule");

        final HttpServerResponseMock response = new HttpServerResponseMock();
        class PUTValidationResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return VALIDATION_URI;
            }

            @Override public String path() {
                return VALIDATION_URI;
            }

            @Override
            public HttpServerResponseMock response() {
                return response;
            }
        }

        PUTValidationResourceRequest request = new PUTValidationResourceRequest();
        request.setBodyContent(VALIDATION_RESOURCE_UPDATE_INVALID);

        boolean handled = validationResourceManager.handleValidationResource(request);
        context.assertTrue(handled, "PUT Request to validation resource should be handled");

        res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "After the update, the resources should not be empty");
        context.assertEquals(1, res.getResources().size(), "After update, there should still be 1 validation rule");

        context.assertEquals(StatusCode.BAD_REQUEST.getStatusCode(), request.response().getStatusCode(), "StatusCode should be 400");
        context.assertEquals(StatusCode.BAD_REQUEST.getStatusMessage(), request.response().getStatusMessage(), "StatusMessage should be Bad Request");

        /* check storage content */
        storage.get(VALIDATION_URI, buffer -> {
            String buffAsString = buffer.toString("UTF-8");
            context.assertEquals(VALIDATION_RESOURCE_VALID, buffAsString, "Updated validation resource from storage should be equal to the original resource");
            async.complete();
        });
    }

    @Test
    public void testHandleValidationResourceWithPUTRequestToOtherResource(TestContext context){
        validationResourceManager = new ValidationResourceManager(vertx, new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_VALID)), VALIDATION_URI);

        ValidationResource res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "Creating ValidationResourceManager with a valid Resource should not result in an empty resources list");

        class PUTOtherResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return "/some/other/resource";
            }

            @Override public String path() {
                return "/some/other/resource";
            }
        }
        boolean handled = validationResourceManager.handleValidationResource(new PUTOtherResourceRequest());
        context.assertFalse(handled, "PUT Request to some other resource should not be handled");

        res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "Creating ValidationResourceManager with a valid Resource should not result in an empty resources list");
    }

    @Test
    public void testHandleValidationResourceWithGETRequests(TestContext context){
        validationResourceManager = new ValidationResourceManager(vertx, new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_VALID)), VALIDATION_URI);

        class GETValidationResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return VALIDATION_URI;
            }

            @Override public String path() {
                return VALIDATION_URI;
            }

        }
        boolean handled = validationResourceManager.handleValidationResource(new GETValidationResourceRequest());
        context.assertFalse(handled, "GET Request to validationUri should not be handled");

        class GETOtherResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/some/other/resource";
            }

            @Override public String path() {
                return "/some/other/resource";
            }
        }
        handled = validationResourceManager.handleValidationResource(new GETOtherResourceRequest());
        context.assertFalse(handled, "GET Request to some other resource should not be handled");
    }

    @Test
    public void testHandleValidationResourceWithDeleteRequests(TestContext context){
        validationResourceManager = new ValidationResourceManager(vertx, new MockResourceStorage(ImmutableMap.of(VALIDATION_URI, VALIDATION_RESOURCE_VALID)), VALIDATION_URI);

        ValidationResource res = validationResourceManager.getValidationResource();
        context.assertFalse(res.getResources().isEmpty(), "Creating ValidationResourceManager with a valid Resource should not result in an empty resources list");

        class DeleteValidationResourceRequest extends HttpServerRequestMock {
            @Override public HttpMethod method() {
                return HttpMethod.DELETE;
            }

            @Override public String uri() {
                return VALIDATION_URI;
            }

            @Override public String path() {
                return VALIDATION_URI;
            }
        }
        boolean handled = validationResourceManager.handleValidationResource(new DeleteValidationResourceRequest());
        context.assertFalse(handled, "DELETE Request to validationUri should not be handled");

        res = validationResourceManager.getValidationResource();
        context.assertTrue(res.getResources().isEmpty(), "After deleting the validation resource, the resources list should be empty");
    }
}
