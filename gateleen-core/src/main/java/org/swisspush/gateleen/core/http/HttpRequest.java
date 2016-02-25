package org.swisspush.gateleen.core.http;

import org.swisspush.gateleen.core.json.JsonMultiMap;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Arrays;

/**
 * Represents a "disconnected" request, i.e. containing the full payload.
 * 
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class HttpRequest {

    /**
     * The HTTP method for the request. One of GET, PUT, POST, DELETE, TRACE, CONNECT, OPTIONS or HEAD
     */
    private HttpMethod method;

    /**
     * The uri of the request. For example http://www.somedomain.com/somepath/somemorepath /somresource.foo?someparam=32&someotherparam=x
     */
    private String uri;

    /**
     * The content of the request stream.
     */
    private byte[] payload;

    /**
     * A map of all headers in the request, If the request contains multiple headers with the same key, the values will be concatenated together into a single header with the same key value, with each
     * value separated by a comma, as specified <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.2" >here</a>.
     */
    private MultiMap headers;

    public HttpRequest(HttpMethod method, String uri, MultiMap headers, byte[] payload) {
        this.method = method;
        this.uri = uri;
        this.headers = headers;

        if (payload == null) {
            this.payload = new byte[0];
        } else {
            this.payload = Arrays.copyOf(payload, payload.length);
        }
    }

    /**
     * @param object object
     */
    public HttpRequest(JsonObject object) {
        this.method = HttpMethod.valueOf(object.getString("method"));
        this.uri = object.getString("uri");
        if (method == null || uri == null) {
            throw new IllegalArgumentException("Request fields 'uri' and 'method' must be set");
        }
        switch (method) {
        case GET:
        case PUT:
        case POST:
        case DELETE:
            break;
        default:
            throw new IllegalArgumentException("Request method must be one of GET, PUT, POST or DELETE");
        }
        JsonArray headersArray = object.getJsonArray("headers");
        if (headersArray != null) {
            this.headers = JsonMultiMap.fromJson(headersArray);
        }
        this.payload = object.getBinary("payload");
    }

    public JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        object.put("method", method);
        object.put("uri", uri);
        if (headers != null) {
            object.put("headers", JsonMultiMap.toJson(headers));
        }
        object.put("payload", payload);
        return object;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public String getUri() {
        return uri;
    }

    public byte[] getPayload() {
        return payload;
    }

    public MultiMap getHeaders() {
        return headers;
    }

    public void setHeaders(MultiMap headers) {
        this.headers = headers;
    }
}
