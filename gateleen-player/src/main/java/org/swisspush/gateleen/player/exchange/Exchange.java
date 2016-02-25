package org.swisspush.gateleen.player.exchange;

import com.google.common.base.Predicate;
import com.jayway.jsonpath.JsonPath;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.*;
import uk.co.datumedge.hamcrest.json.SameJSONAs;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

/**
 * Models a request log entry.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class Exchange {

    private RequestEntity<JSONObject> request;
    private ResponseEntity<JSONObject> response;

    public Exchange(RequestEntity<JSONObject> request, ResponseEntity<JSONObject> response) {
        this.request = request;
        this.response = response;
    }

    /**
     * @param urlPrefix A prefix added to the request URI.
     * @param json Source exchange in JSON form.
     */
    public Exchange(String urlPrefix, JSONObject json) {
        JSONObject jsonRequest = null;
        try {
            jsonRequest = json.getJSONObject("request");
            if (jsonRequest == null) {
                jsonRequest = new JSONObject();
            }
            JSONObject jsonResponse = json.getJSONObject("response");
            if (jsonResponse == null) {
                jsonResponse = new JSONObject();
            }
            JSONObject body = null;
            JSONObject headers = null;
            try {
                if (jsonRequest.has("body")) {
                    body = jsonRequest.getJSONObject("body");
                }
                if (jsonRequest.has("headers")) {
                    headers = jsonRequest.getJSONObject("headers");
                }
                request = new RequestEntity<>(
                        body,
                        createHeaders(headers),
                        HttpMethod.valueOf(json.getString("method")),
                        new URI(urlPrefix + json.getString("url")));
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
            body = null;
            headers = null;
            if (jsonResponse.has("body")) {
                body = jsonResponse.getJSONObject("body");
            }
            if (jsonResponse.has("headers")) {
                headers = jsonResponse.getJSONObject("headers");
            }
            response = new ResponseEntity<>(
                    body,
                    createHeaders(headers),
                    HttpStatus.valueOf(json.getInt("statusCode")));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public RequestEntity<JSONObject> getRequest() {
        return request;
    }

    public void setRequest(RequestEntity<JSONObject> request) {
        this.request = request;
    }

    public ResponseEntity<JSONObject> getResponse() {
        return response;
    }

    public void setResponse(ResponseEntity<JSONObject> response) {
        this.response = response;
    }

    private HttpHeaders createHeaders(JSONObject jsonHeaders) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (headers != null) {
                Iterator it = jsonHeaders.keys();
                while (it.hasNext()) {
                    String key = (String) it.next();
                    headers.add(key, jsonHeaders.getString(key));
                }
            }
            return headers;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convenience method to retrieve the <code>x-client-timestamp</code> header.
     *
     * @return the ISO timestamp string
     */
    public String getTimestamp() {
        return request.getHeaders().getFirst("x-client-timestamp");
    }

    /**
     * Convenience method to retrieve the <code>x-request-id</code> header.
     *
     * @return the ID
     */
    public String getId() {
        return request.getHeaders().getFirst("x-request-id");
    }

    /**
     * Predicate that applies to the request part.
     * 
     * @param requestPredicate requestPredicate
     * @return Predicate
     */
    public static Predicate<Exchange> request(final Predicate<? super RequestEntity<JSONObject>> requestPredicate) {
        return new Predicate<Exchange>() {
            @Override
            public boolean apply(Exchange exchange) {
                return requestPredicate.apply(exchange.getRequest());
            }
        };
    }

    /**
     * Predicate that applies to the response part.
     * 
     * @param responsePredicate responsePredicate
     * @return Predicate
     */
    public static Predicate<Exchange> response(final Predicate<? super ResponseEntity<JSONObject>> responsePredicate) {
        return new Predicate<Exchange>() {
            @Override
            public boolean apply(Exchange exchange) {
                return responsePredicate.apply(exchange.getResponse());
            }
        };
    }

    /**
     * Predicate matching exchanges having one of the specified <code>x-request-id</code> header.
     * 
     * @param requestIds requestIds
     * @return Predicate
     */
    public static Predicate<Exchange> withId(final String... requestIds) {
        return new Predicate<Exchange>() {
            @Override
            public boolean apply(Exchange exchange) {
                boolean result = false;
                for (String requestId : requestIds) {
                    result |= requestId.equals(exchange.getRequest().getHeaders().getFirst("x-request-id"));
                }
                return result;
            }
        };
    }

    /**
     * Predicate that applies to the request URL.
     * 
     * @param stringPredicate stringPredicate
     * @return Predicate
     */
    public static Predicate<RequestEntity<JSONObject>> url(final Predicate<? super CharSequence> stringPredicate) {
        return new Predicate<RequestEntity<JSONObject>>() {
            @Override
            public boolean apply(RequestEntity<JSONObject> request) {
                return stringPredicate.apply(request.getUrl().toString());
            }
        };
    }

    /**
     * Predicate that applies to the request HTTP method.
     * 
     * @param methodPredicate methodPredicate
     * @return Predicate
     */
    public static Predicate<RequestEntity<JSONObject>> method(final Predicate<HttpMethod> methodPredicate) {
        return new Predicate<RequestEntity<JSONObject>>() {
            @Override
            public boolean apply(RequestEntity<JSONObject> request) {
                return methodPredicate.apply(request.getMethod());
            }
        };
    }

    /**
     * Predicate that applies to the response HTTP status code.
     * 
     * @param statusPredicate statusPredicate
     * @return Predicate
     */
    public static Predicate<ResponseEntity<JSONObject>> status(final Predicate<HttpStatus> statusPredicate) {
        return new Predicate<ResponseEntity<JSONObject>>() {
            @Override
            public boolean apply(ResponseEntity<JSONObject> response) {
                return statusPredicate.apply(response.getStatusCode());
            }
        };
    }

    /**
     * Predicate that applies to a request or response body.
     * 
     * @param bodyPredicate bodyPredicate
     * @return Predicate
     */
    public static <T extends HttpEntity<JSONObject>> Predicate<T> body(final com.jayway.jsonpath.Predicate bodyPredicate) {
        return new Predicate<T>() {
            JsonPath path;

            @Override
            public boolean apply(T entity) {
                if (entity.getBody() == null) {
                    return false;
                }
                if (path == null) {
                    path = JsonPath.compile("$[?]", bodyPredicate);
                }
                return !((List) path.read(entity.getBody().toString())).isEmpty();
            }
        };
    }

    /**
     * Predicate that applies to request or response headers.
     * 
     * @param headersPredicate headersPredicate
     * @return Predicate
     */
    public static <T extends HttpEntity<JSONObject>> Predicate<T> headers(final Predicate<HttpHeaders> headersPredicate) {
        return new Predicate<T>() {
            @Override
            public boolean apply(T entity) {
                return headersPredicate.apply(entity.getHeaders());
            }
        };
    }

    /**
     * Predicate that applies to a given request or response header.
     * 
     * @param key key
     * @param stringPredicate stringPredicate
     * @return Predicate
     */
    public static <T extends HttpEntity<JSONObject>, U extends CharSequence> Predicate<T> header(final String key, final Predicate<U> stringPredicate) {
        return new Predicate<T>() {
            @Override
            public boolean apply(T entity) {
                List<String> found = entity.getHeaders().get(key);
                if (found != null && !found.isEmpty()) {
                    return stringPredicate.apply((U) found.get(0));
                } else {
                    return false;
                }
            }
        };
    }

    /**
     * Convenience method for unit tests.
     * 
     * @param expected expected
     * @param actual actual
     */
    public static void assertSameExchange(Exchange expected, Exchange actual) {
        if (expected == null) {
            assertThat("Actual exchange should be null", actual, nullValue());
        } else {
            assertThat("Actual exchange should not be null", actual, notNullValue());

            assertThat("Expected request: " + expected.getRequest() + ", Actual request: " + actual.getRequest(), expected.getRequest().getUrl(), equalTo(actual.getRequest().getUrl()));
            assertThat("Expected request: " + expected.getRequest() + ", Actual request: " + actual.getRequest(), expected.getRequest().getMethod(), equalTo(actual.getRequest().getMethod()));
            assertThat("Expected request: " + expected.getRequest() + ", Actual request: " + actual.getRequest(), expected.getRequest().getHeaders(), equalTo(actual.getRequest().getHeaders()));
            if (expected.getRequest().getBody() != null && actual.getRequest().getBody() != null) {
                assertThat("Actual request body should be null. Actual request: " + actual.getRequest(), expected.getRequest().getBody(), notNullValue());
                assertThat("Actual request body should not be null. Expected request: " + expected.getRequest(), actual.getRequest().getBody(), notNullValue());
                assertThat("Request body do not match. Expected request: " + expected.getRequest() + ", Actual request: " + actual.getRequest(), expected.getRequest().getBody(), SameJSONAs.sameJSONObjectAs(actual.getRequest().getBody()));
            }

            assertThat("Expected response: " + expected.getResponse() + ", Actual response: " + actual.getResponse(), expected.getResponse().getStatusCode(), equalTo(actual.getResponse().getStatusCode()));
            assertThat("Expected response: " + expected.getResponse() + ", Actual response: " + actual.getResponse(), expected.getResponse().getHeaders(), equalTo(actual.getResponse().getHeaders()));
            if (expected.getResponse().getBody() != null && actual.getResponse().getBody() != null) {
                assertThat("Actual response body should be null. Actual response: " + actual.getResponse(), expected.getResponse().getBody(), notNullValue());
                assertThat("Actual response body should not be null. Expected response: " + expected.getResponse(), actual.getResponse().getBody(), notNullValue());
                assertThat("Response body do not match. Expected response: " + expected.getResponse() + ", Actual response: " + actual.getResponse(), expected.getResponse().getBody(), SameJSONAs.sameJSONObjectAs(actual.getRequest().getBody()));
            }

            assertThat(expected.getRequest().getUrl(), equalTo(actual.getRequest().getUrl()));
        }
    }

    @Override
    public String toString() {
        return "request=" + request + ", response=" + response;
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        // general check
        if (o == this) {
            return true;
        }

        if (o == null) {
            return false;
        }

        if (o.getClass() != getClass()) {
            return false;
        }

        // specific check
        Exchange exchange2 = (Exchange) o;

        // compare request url, method and headers
        boolean equal = this.getRequest().getUrl().equals(exchange2.getRequest().getUrl()) &&
                this.getRequest().getMethod().equals(exchange2.getRequest().getMethod()) &&
                this.getRequest().getHeaders().equals(exchange2.getRequest().getHeaders());

        if (equal) {
            // request Body available?
            if (this.getRequest().getBody() != null && exchange2.getRequest().getBody() != null) {
                // compare request body
                SameJSONAs<JSONObject> body = SameJSONAs.sameJSONObjectAs(this.getRequest().getBody());
                equal = body.matches(exchange2.getRequest().getBody());
            } else if ((this.getRequest().getBody() != null && exchange2.getRequest().getBody() == null) ||
                    (this.getRequest().getBody() == null && exchange2.getRequest().getBody() != null)) {
                equal = false;
            }

            // compare response
            if (equal) {
                // compare response statuscode and headers
                equal = this.getResponse().getStatusCode().equals(exchange2.getResponse().getStatusCode()) &&
                        this.getResponse().getHeaders().equals(exchange2.getResponse().getHeaders());

                if (equal) {
                    // response Body available?
                    if (this.getResponse().getBody() != null && exchange2.getResponse().getBody() != null) {
                        // compare response body
                        SameJSONAs<JSONObject> body = SameJSONAs.sameJSONObjectAs(this.getResponse().getBody());
                        equal = body.matches(exchange2.getResponse().getBody());
                    } else if ((this.getResponse().getBody() != null && exchange2.getResponse().getBody() == null) ||
                            (this.getResponse().getBody() == null && exchange2.getResponse().getBody() != null)) {
                        equal = false;
                    }
                }
            }
        }

        return equal;
    }
}
