package org.swisspush.gateleen.logging;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;

/**
 * Tests for the {@link RequestPropertyFilter} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RequestPropertyFilterTest {

    private final String METHOD_PUT = "PUT";
    private final String METHOD_PUT_POST_REGEX = "PUT|POST";
    private final String METHOD_GET = "GET";
    private final String PUT_REQUEST_REGEX = "/playground/server/.*";
    private final String PUT_REQUEST_URI = "/playground/server/some_resource";
    private final String OTHER_PUT_REQUEST_URI = "/playground/server/some_other_resource";

    @Test
    public void testPropertyUrlFilterRequest(TestContext context){
        context.assertEquals(FilterResult.FILTER,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.URL, PUT_REQUEST_REGEX, false));
    }

    @Test
    public void testPropertyUrlRejectRequest(TestContext context){
        context.assertEquals(FilterResult.REJECT,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.URL, PUT_REQUEST_REGEX, true));
    }

    @Test
    public void testPropertyUrlNoMatchRequest(TestContext context){
        context.assertEquals(FilterResult.NO_MATCH,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.URL, OTHER_PUT_REQUEST_URI, true));
    }

    @Test
    public void testPropertyMethodFilterRequest(TestContext context){
        context.assertEquals(FilterResult.FILTER,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, METHOD_PUT, false));

        context.assertEquals(FilterResult.FILTER,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, METHOD_PUT_POST_REGEX, false));
    }

    @Test
    public void testPropertyMethodRejectRequest(TestContext context){
        context.assertEquals(FilterResult.REJECT,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, METHOD_PUT, true));
    }

    @Test
    public void testPropertyMethodNoMatchRequest(TestContext context){
        context.assertEquals(FilterResult.NO_MATCH,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, METHOD_GET, false));

        // check again with reject = true
        context.assertEquals(FilterResult.NO_MATCH,
                RequestPropertyFilter.filterProperty(new PUTRequest(), RequestPropertyFilter.METHOD, METHOD_GET, true));
    }

    @Test
    public void testPropertyHeaderFilterRequest(TestContext context){
        PUTRequest request = new PUTRequest();
        String headerName = "some_fancy_header";
        String headerValue = "a_fancy_value";
        request.addHeader(headerName, headerValue);

        context.assertEquals(FilterResult.FILTER, RequestPropertyFilter.filterProperty(request, headerName, headerValue, false));
    }

    @Test
    public void testPropertyHeaderRejectRequest(TestContext context){
        PUTRequest request = new PUTRequest();
        String headerName = "some_fancy_header";
        String headerValue = "a_fancy_value";
        request.addHeader(headerName, headerValue);

        context.assertEquals(FilterResult.REJECT, RequestPropertyFilter.filterProperty(request, headerName, headerValue, true));
    }

    @Test
    public void testPropertyHeaderNotMatchingRequest(TestContext context){
        PUTRequest request = new PUTRequest();
        String headerName = "some_fancy_header";
        String headerValue = "a_fancy_value";
        request.addHeader(headerName, headerValue);

        // reject = true
        context.assertEquals(FilterResult.REJECT, RequestPropertyFilter.filterProperty(request, headerName, "another_fancy_value", true));
        context.assertEquals(FilterResult.REJECT, RequestPropertyFilter.filterProperty(request, "another_fancy_header", headerValue, true));

        // reject = false
        context.assertEquals(FilterResult.REJECT, RequestPropertyFilter.filterProperty(request, headerName, "another_fancy_value", false));
        context.assertEquals(FilterResult.REJECT, RequestPropertyFilter.filterProperty(request, "another_fancy_header", headerValue, false));
    }

    class PUTRequest extends DummyHttpServerRequest {
        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();

        @Override public HttpMethod method() {
            return HttpMethod.PUT;
        }
        @Override public String uri() {
            return PUT_REQUEST_URI;
        }
        @Override public MultiMap headers() { return headers; }

        public void addHeader(String headerName, String headerValue){ headers.add(headerName, headerValue); }
    }
}
