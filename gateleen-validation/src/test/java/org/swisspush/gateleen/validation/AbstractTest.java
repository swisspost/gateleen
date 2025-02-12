package org.swisspush.gateleen.validation;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import org.mockito.Mockito;
import org.swisspush.gateleen.validation.mocks.HttpServerRequestMock;

public abstract class AbstractTest {

    private static final JsonSchemaFactory JSON_SCHEMA_FACTORY = JsonSchemaFactory.getInstance();

    protected JsonSchema createSchema(String dataString) {
        return JSON_SCHEMA_FACTORY.getSchema(dataString);
    }

    static class CustomHttpServerRequest extends HttpServerRequestMock {

        private final HttpMethod method;
        private final String uri;
        private final String path;
        private final HttpServerResponse response;

        public CustomHttpServerRequest(HttpMethod method, String uri) {
            this(method, uri, uri);
        }

        public CustomHttpServerRequest(HttpMethod method, String uri, HttpServerResponse response) {
            this(method, uri, uri, response);
        }

        public CustomHttpServerRequest(HttpMethod method, String uri, String path) {
            this(method, uri, path, Mockito.mock(HttpServerResponse.class));
        }

        public CustomHttpServerRequest(HttpMethod method, String uri, String path, HttpServerResponse response) {
            this.method = method;
            this.uri = uri;
            this.path = path;
            this.response = response;
        }

        @Override
        public HttpMethod method() {
            return method;
        }

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public MultiMap headers() {
            return super.headers();
        }

        @Override
        public HttpServerResponse response() {
            return response;
        }
    }
}
