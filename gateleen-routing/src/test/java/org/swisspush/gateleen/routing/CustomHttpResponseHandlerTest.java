package org.swisspush.gateleen.routing;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.util.StatusCode;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.times;

/**
 * Tests for the {@link CustomHttpResponseHandler} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class CustomHttpResponseHandlerTest {

    private CustomHttpResponseHandler responseHandler;

    @Before
    public void setUp(){
        responseHandler = new CustomHttpResponseHandler("/gateleen/server/return-with-status-code");
    }

    @Test
    public void testRequestNotHandled(TestContext context){
        HttpServerRequest request = new HttpServerRequest("/gateleen/server/someResource", mock(HttpServerResponse.class));
        final boolean handled = responseHandler.handle(request);
        context.assertFalse(handled, "Request should not have been handled");
    }

    @Test
    public void testRequestHandledWithStatus503(TestContext context){
        HttpServerResponse response = spy(new DummyHttpServerResponse());
        HttpServerRequest request = new HttpServerRequest("/gateleen/server/return-with-status-code/503", response);

        final boolean handled = responseHandler.handle(request);
        context.assertTrue(handled, "Request should have been handled");

        verify(response, times(1)).setStatusCode(eq(StatusCode.SERVICE_UNAVAILABLE.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.SERVICE_UNAVAILABLE.getStatusMessage()));
    }

    @Test
    public void testRequestHandledWithStatus405AndAdditionalPathSegments(TestContext context){
        HttpServerResponse response = spy(new DummyHttpServerResponse());
        HttpServerRequest request = new HttpServerRequest("/gateleen/server/return-with-status-code/405/ignored/path/segments", response);

        final boolean handled = responseHandler.handle(request);
        context.assertTrue(handled, "Request should have been handled");

        verify(response, times(1)).setStatusCode(eq(StatusCode.METHOD_NOT_ALLOWED.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage()));
    }

    @Test
    public void testRequestHandledWithInvalidStatus(TestContext context){
        HttpServerResponse response = spy(new DummyHttpServerResponse());
        HttpServerRequest request = new HttpServerRequest("/gateleen/server/return-with-status-code/not_a_number", response);

        final boolean handled = responseHandler.handle(request);
        context.assertTrue(handled, "Request should have been handled");

        verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.BAD_REQUEST.getStatusMessage()));
        verify(response, times(1)).end(eq("400 Bad Request: missing, wrong or non-numeric status-code in request URL"));
    }

    @Test
    public void testRequestHandledWithUnknownStatus(TestContext context){
        HttpServerResponse response = spy(new DummyHttpServerResponse());
        HttpServerRequest request = new HttpServerRequest("/gateleen/server/return-with-status-code/1234", response);

        final boolean handled = responseHandler.handle(request);
        context.assertTrue(handled, "Request should have been handled");

        verify(response, times(1)).setStatusCode(eq(1234));
        verify(response, times(1)).setStatusMessage(eq("Unknown Status (1234)"));
    }

    @Test
    public void testRequestHandledWithMissingStatus(TestContext context){
        HttpServerResponse response = spy(new DummyHttpServerResponse());
        HttpServerRequest request = new HttpServerRequest("/gateleen/server/return-with-status-code/", response);

        final boolean handled = responseHandler.handle(request);
        context.assertTrue(handled, "Request should have been handled");

        verify(response, times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));
        verify(response, times(1)).setStatusMessage(eq(StatusCode.BAD_REQUEST.getStatusMessage()));
        verify(response, times(1)).end(eq("400 Bad Request: missing, wrong or non-numeric status-code in request URL"));
    }

    private static class HttpServerRequest extends DummyHttpServerRequest {

        private String uri;
        private HttpServerResponse response;

        public HttpServerRequest(String uri, HttpServerResponse response) {
            this.uri = uri;
            this.response = response;
        }

        @Override public HttpMethod method() {
            return HttpMethod.GET;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public HttpServerResponse response() { return response; }
    }

}
