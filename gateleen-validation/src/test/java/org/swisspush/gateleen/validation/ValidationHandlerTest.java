package org.swisspush.gateleen.validation;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.Optional;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test class for the ValidationHandler
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ValidationHandlerTest extends AbstractTest {

    private Vertx vertx;
    private HttpClient httpClient;
    private HttpClientRequest clientRequest;
    private ValidationResourceManager validationResourceManager;
    private ValidationSchemaProvider validationSchemaProvider;
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

    private final String RESOURCE_GET_PUT_SCHEMA_LOCATION = "{\n" +
            "  \"resources\": [{\n" +
            "    \"url\": \"/gateleen/resources/someResource\",\n" +
            "    \"method\": \"GET|PUT\",\n" +
            "    \"schema\": {\n" +
            "      \"location\": \"/gateleen/path/to/the/schema\"\n" +
            "    }\n" +
            "  }]\n" +
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

    private final String SCHEMA = "{\n" +
            "  \"$schema\": \"http://json-schema.org/draft-04/schema#\",\n" +
            "  \"properties\": {\n" +
            "    \"key\": {\n" +
            "      \"maxLength\": 5,\n" +
            "      \"type\": \"string\"\n" +
            "    }\n" +
            "  },\n" +
            "  \"required\": [\n" +
            "    \"key\"\n" +
            "  ],\n" +
            "  \"additionalProperties\": false,\n" +
            "  \"definitions\": {}\n" +
            "}";

    @Before
    public void setUp() {
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));

        httpClient = Mockito.mock(HttpClient.class);
        clientRequest = Mockito.mock(HttpClientRequest.class);
        Mockito.when(clientRequest.headers()).thenReturn(new HeadersMultiMap());
        Mockito.when(httpClient.request(any(HttpMethod.class), anyString()))
                .thenReturn(Future.succeededFuture(clientRequest));

        storage = new MockResourceStorage();
        validationResourceManager = new ValidationResourceManager(vertx, storage, VALIDATION_URI);
        validationSchemaProvider = Mockito.mock(ValidationSchemaProvider.class);
    }

    private void sendValidationResourcesUpdate(String validationResource) {
        CustomHttpServerRequest request = new CustomHttpServerRequest(HttpMethod.PUT, VALIDATION_URI);
        request.setBodyContent(validationResource);
        validationResourceManager.handleValidationResource(request);
    }

    @Test
    public void testIsToValidate(TestContext context) {
        storage.putMockData(VALIDATION_URI, RESOURCE_GET_PUT);
        ValidationHandler validationHandler = new ValidationHandler(validationResourceManager, validationSchemaProvider, storage, httpClient, SCHEMA_ROOT);

        sendValidationResourcesUpdate(RESOURCE_GET_PUT);

        CustomHttpServerRequest putToUnmanagedResourceRequest = new CustomHttpServerRequest(HttpMethod.PUT, "/some/other/resource");
        context.assertFalse(validationHandler.isToValidate(putToUnmanagedResourceRequest),
                "PUT Requests to not managed resources should not be validated");

        CustomHttpServerRequest putToGateleenSomeResourceRequest = new CustomHttpServerRequest(HttpMethod.PUT, "/gateleen/resources/someResource");
        context.assertTrue(validationHandler.isToValidate(putToGateleenSomeResourceRequest), "PUT Requests to some resource should be validated");

        sendValidationResourcesUpdate(RESOURCE_GET);
        context.assertFalse(validationHandler.isToValidate(putToGateleenSomeResourceRequest), "Now, PUT Requests to some resource should not be validated anymore (only GET Requests)");
        context.assertFalse(validationHandler.isToValidate(putToUnmanagedResourceRequest), "PUT Requests to not managed resources should not be validated");

        CustomHttpServerRequest getToGateleenSomeResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/gateleen/resources/someResource");
        context.assertTrue(validationHandler.isToValidate(getToGateleenSomeResourceRequest), "GET Requests to some resource should be validated");

        sendValidationResourcesUpdate(OTHER_RESOURCES_GET_PUT);
        context.assertFalse(validationHandler.isToValidate(getToGateleenSomeResourceRequest), "GET Requests to some resource should not be validated");
        context.assertTrue(validationHandler.isToValidate(putToGateleenSomeResourceRequest), "PUT Requests to some resource should be validated");

        context.assertFalse(validationHandler.isToValidate(putToUnmanagedResourceRequest), "PUT Requests to not managed resources should not be validated");

        CustomHttpServerRequest putToGateleenOtherResourceRequest = new CustomHttpServerRequest(HttpMethod.PUT, "/gateleen/resources/otherResource");
        CustomHttpServerRequest getToGateleenOtherResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/gateleen/resources/otherResource");

        context.assertTrue(validationHandler.isToValidate(getToGateleenOtherResourceRequest), "GET Requests to other resource should be validated");
        context.assertFalse(validationHandler.isToValidate(putToGateleenOtherResourceRequest), "PUT Requests to other resource should not be validated");
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
    public void testIsToValidateSchemaLocation(TestContext context) {
        storage.putMockData(VALIDATION_URI, RESOURCE_GET_PUT_SCHEMA_LOCATION);
        ValidationHandler validationHandler = new ValidationHandler(validationResourceManager, validationSchemaProvider, storage, httpClient, SCHEMA_ROOT);

        sendValidationResourcesUpdate(RESOURCE_GET_PUT_SCHEMA_LOCATION);

        CustomHttpServerRequest putToUnmanagedResourceRequest = new CustomHttpServerRequest(HttpMethod.PUT, "/some/other/resource");
        context.assertFalse(validationHandler.isToValidate(putToUnmanagedResourceRequest), "PUT Requests to not managed resources should not be validated");
        CustomHttpServerRequest getToUnmanagedResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/some/other/resource");
        context.assertFalse(validationHandler.isToValidate(getToUnmanagedResourceRequest), "GET Requests to not managed resources should not be validated");

        CustomHttpServerRequest putToGateleenSomeResourceRequest = new CustomHttpServerRequest(HttpMethod.PUT, "/gateleen/resources/someResource");
        context.assertTrue(validationHandler.isToValidate(putToGateleenSomeResourceRequest), "PUT Requests to some resource should be validated");
        CustomHttpServerRequest getToGateleenSomeResourceRequest = new CustomHttpServerRequest(HttpMethod.GET, "/gateleen/resources/someResource");
        context.assertTrue(validationHandler.isToValidate(getToGateleenSomeResourceRequest), "GET Requests to some resource should be validated");
    }

    @Test
    public void testValidateSchemaLocationNoSchemaFound(TestContext context) {
        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class))).thenReturn(Future.succeededFuture(Optional.empty()));
        storage.putMockData(VALIDATION_URI, RESOURCE_GET_PUT_SCHEMA_LOCATION);
        ValidationHandler validationHandler = new ValidationHandler(validationResourceManager, validationSchemaProvider, storage, httpClient, SCHEMA_ROOT);

        sendValidationResourcesUpdate(RESOURCE_GET_PUT_SCHEMA_LOCATION);

        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        CustomHttpServerRequest request = new CustomHttpServerRequest(HttpMethod.PUT, "/gateleen/resources/someResource", response);
        request.setBodyContent("{}");

        validationHandler.handle(request);

        verify(response, times(1)).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
        verify(response, times(1)).end("No schema found in location /gateleen/path/to/the/schema");
    }

    @Test
    public void testValidateSchemaLocationSchemaProviderError(TestContext context) {
        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class))).thenReturn(Future.failedFuture("Boooom"));
        storage.putMockData(VALIDATION_URI, RESOURCE_GET_PUT_SCHEMA_LOCATION);
        ValidationHandler validationHandler = new ValidationHandler(validationResourceManager, validationSchemaProvider, storage, httpClient, SCHEMA_ROOT);

        sendValidationResourcesUpdate(RESOURCE_GET_PUT_SCHEMA_LOCATION);

        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        CustomHttpServerRequest request = new CustomHttpServerRequest(HttpMethod.PUT, "/gateleen/resources/someResource", response);
        request.setBodyContent("{}");

        validationHandler.handle(request);

        verify(response, times(1)).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
        verify(response, times(1)).end("Error while getting schema. Cause: Boooom");
    }

    @Test
    public void testValidateSchemaLocationWithInvalidPayload(TestContext context) {
        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class))).thenReturn(Future.succeededFuture(Optional.of(createSchema(SCHEMA))));
        storage.putMockData(VALIDATION_URI, RESOURCE_GET_PUT_SCHEMA_LOCATION);
        ValidationHandler validationHandler = new ValidationHandler(validationResourceManager, validationSchemaProvider, storage, httpClient, SCHEMA_ROOT);

        sendValidationResourcesUpdate(RESOURCE_GET_PUT_SCHEMA_LOCATION);

        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        when(response.headers()).thenReturn(new HeadersMultiMap());
        CustomHttpServerRequest request = new CustomHttpServerRequest(HttpMethod.PUT, "/gateleen/resources/someResource", response);
        request.setBodyContent("{\"key\": \"12345xx\"}");

        validationHandler.handle(request);

        verify(response, times(1)).setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
        verify(response, times(1)).setStatusMessage(StatusCode.BAD_REQUEST.getStatusMessage());
        verify(response, times(1)).end(contains("$.key: may only be 5 characters long"));
    }

    @Test
    public void testValidateSchemaLocationWithValidPayload(TestContext context) {
        when(validationSchemaProvider.schemaFromLocation(any(SchemaLocation.class))).thenReturn(Future.succeededFuture(Optional.of(createSchema(SCHEMA))));
        storage.putMockData(VALIDATION_URI, RESOURCE_GET_PUT_SCHEMA_LOCATION);
        ValidationHandler validationHandler = new ValidationHandler(validationResourceManager, validationSchemaProvider, storage, httpClient, SCHEMA_ROOT);

        sendValidationResourcesUpdate(RESOURCE_GET_PUT_SCHEMA_LOCATION);

        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        when(response.headers()).thenReturn(new HeadersMultiMap());
        CustomHttpServerRequest request = new CustomHttpServerRequest(HttpMethod.PUT, "/gateleen/resources/someResource", response);
        request.setBodyContent("{\"key\": \"12345\"}");

        validationHandler.handle(request);

        verifyZeroInteractions(response);
    }

    @Test
    public void testIsToValidateHooks(TestContext context) {
        storage.putMockData(VALIDATION_URI, RESOURCE_GET_PUT_GENERIC);
        ValidationHandler validationHandler = new ValidationHandler(validationResourceManager, validationSchemaProvider, storage, httpClient, SCHEMA_ROOT);

        sendValidationResourcesUpdate(RESOURCE_GET_PUT_GENERIC);

        // GET to Hooks resource
        context.assertFalse(validationHandler.isToValidate(new CustomHttpServerRequest(HttpMethod.GET,
                "/gateleen/trip/v1/destinations/current/_hooks/listeners/http/linti-06614431506022811")), "GET Requests to hooks should not be validated");

        // GET to Hooks route resource
        context.assertFalse(validationHandler.isToValidate(new CustomHttpServerRequest(HttpMethod.GET,
                "/gateleen/trip/v1/destinations/current/_hooks/route/http/linti-06614431506022811")), "GET Requests to hooks/routes should not be validated");

        // GET to gateleen resource
        context.assertTrue(validationHandler.isToValidate(new CustomHttpServerRequest(HttpMethod.GET,
                "/gateleen/trip/v1/destinations/current/11")), "GET Requests with wildcard should be validated");
    }
}

