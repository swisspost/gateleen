package org.swisspush.gateleen.validation;

import org.swisspush.gateleen.validation.mocks.HttpServerRequestMock;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/**
 * Test class for the ValidationHandler
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ValidationHandlerTest {

    private Vertx vertx;
    private HttpClient httpClient;
    private ValidationResourceManager validationResourceManager;
    private MockResourceStorage storage;

    private final String VALIDATION_URI = "/gateleen/server/validation";
    private final String SCHEMA_ROOT = "/gateleen/schemas/apis/";

    private final String RESOURCE_GET_PUT = "{\n" +
            "  \"resources\": [\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/resources/someResource\",\n" +
            "      \"method\": \"GET|PUT\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private final String RESOURCE_GET = "{\n" +
            "  \"resources\": [\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/resources/someResource\",\n" +
            "      \"method\": \"GET\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private final String OTHER_RESOURCES_GET_PUT = "{\n" +
            "  \"resources\": [\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/resources/someResource\",\n" +
            "      \"method\": \"PUT\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/resources/otherResource\",\n" +
            "      \"method\": \"GET\"\n" +
            "    }    \n" +
            "  ]\n" +
            "}";

    @Before
    public void setUp(){
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));

        httpClient = Mockito.mock(HttpClient.class);

        storage = new MockResourceStorage();
        validationResourceManager = new ValidationResourceManager(vertx, storage, VALIDATION_URI);
    }

    private void sendValidationResourcesUpdate(String validationResource){
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
        request.setBodyContent(validationResource);
        validationResourceManager.handleValidationResource(request);
    }

    @Test
    public void testIsToValidate(TestContext context){
        storage.putMockData(VALIDATION_URI, RESOURCE_GET_PUT);
        ValidationHandler validationHandler = new ValidationHandler(validationResourceManager, storage, httpClient, SCHEMA_ROOT);

        sendValidationResourcesUpdate(RESOURCE_GET_PUT);

        class PUTToUnmanagedResourceRequest extends HttpServerRequestMock{
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
        context.assertFalse(validationHandler.isToValidate(new PUTToUnmanagedResourceRequest()), "PUT Requests to not managed resources should not be validated");

        class PUTToGateleenSomeResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return "/gateleen/resources/someResource";
            }

            @Override public String path() {
                return "/gateleen/resources/someResource";
            }
        }
        context.assertTrue(validationHandler.isToValidate(new PUTToGateleenSomeResourceRequest()), "PUT Requests to some resource should be validated");

        sendValidationResourcesUpdate(RESOURCE_GET);
        context.assertFalse(validationHandler.isToValidate(new PUTToGateleenSomeResourceRequest()), "Now, PUT Requests to some resource should not be validated anymore (only GET Requests)");
        context.assertFalse(validationHandler.isToValidate(new PUTToUnmanagedResourceRequest()), "PUT Requests to not managed resources should not be validated");

        class GETToGateleenSomeResourceRequest extends PUTToGateleenSomeResourceRequest {
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }
        }
        context.assertTrue(validationHandler.isToValidate(new GETToGateleenSomeResourceRequest()), "GET Requests to some resource should be validated");

        sendValidationResourcesUpdate(OTHER_RESOURCES_GET_PUT);
        context.assertFalse(validationHandler.isToValidate(new GETToGateleenSomeResourceRequest()), "GET Requests to some resource should not be validated");
        context.assertTrue(validationHandler.isToValidate(new PUTToGateleenSomeResourceRequest()), "PUT Requests to some resource should be validated");

        context.assertFalse(validationHandler.isToValidate(new PUTToUnmanagedResourceRequest()), "PUT Requests to not managed resources should not be validated");

        class PUTToGateleenOtherResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return "/gateleen/resources/otherResource";
            }

            @Override public String path() {
                return "/gateleen/resources/otherResource";
            }
        }
        class GETToGateleenOtherResourceRequest extends PUTToGateleenOtherResourceRequest {
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }
        }

        context.assertTrue(validationHandler.isToValidate(new GETToGateleenOtherResourceRequest()), "GET Requests to other resource should be validated");
        context.assertFalse(validationHandler.isToValidate(new PUTToGateleenOtherResourceRequest()), "PUT Requests to other resource should not be validated");
    }

    private final String RESOURCE_GET_PUT_GENERIC = "{\n" +
            "  \"resources\": [\n" +
            "    {\n" +
            "      \"url\": \"/gateleen/trip/v1/destinations(.*)\",\n" +
            "      \"method\": \"GET|PUT\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    @Test
    public void testIsToValidateHooks(TestContext context){
        storage.putMockData(VALIDATION_URI, RESOURCE_GET_PUT_GENERIC);
        ValidationHandler validationHandler = new ValidationHandler(validationResourceManager, storage, httpClient, SCHEMA_ROOT);

        sendValidationResourcesUpdate(RESOURCE_GET_PUT_GENERIC);

               class GetToHooksResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override public String uri() {
                return "/gateleen/trip/v1/destinations/current/_hooks/listeners/http/linti-06614431506022811";
            }

            @Override public String path() {
                return "/gateleen/trip/v1/destinations/current/_hooks/listeners/http/linti-06614431506022811";
            }
        }
        context.assertFalse(validationHandler.isToValidate(new GetToHooksResourceRequest()), "GET Requests to hooks should not be validated");

        class GetToHooksRouteResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return "/gateleen/trip/v1/destinations/current/_hooks/route/http/linti-06614431506022811";
            }

            @Override public String path() {
                return "/gateleen/trip/v1/destinations/current/_hooks/route/http/linti-06614431506022811";
            }
        }
        context.assertFalse(validationHandler.isToValidate(new GetToHooksRouteResourceRequest()), "GET Requests to hooks/routes should not be validated");


        class GetGateleenResourceRequest extends HttpServerRequestMock{
            @Override public HttpMethod method() {
                return HttpMethod.PUT;
            }

            @Override public String uri() {
                return "/gateleen/trip/v1/destinations/current/11";
            }

            @Override public String path() {
                return "/gateleen/trip/v1/destinations/current/11";
            }
        }
        context.assertTrue(validationHandler.isToValidate(new GetGateleenResourceRequest()), "GET Requests with wildcard should be validated");
    }
}

