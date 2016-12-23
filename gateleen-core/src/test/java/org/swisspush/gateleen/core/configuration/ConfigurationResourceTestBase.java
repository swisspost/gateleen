package org.swisspush.gateleen.core.configuration;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;

/**
 * Base class containing common test resources, objects and methods
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public abstract class ConfigurationResourceTestBase {

    protected Vertx vertx;

    protected final String PERSON_SCHEMA = "{\n" +
            "\t\"$schema\": \"http://json-schema.org/draft-04/schema#\",\n" +
            "\t\"type\": \"object\",\n" +
            "\t\"properties\": {\n" +
            "\t\t\"firstName\": {\n" +
            "\t\t\t\"type\": \"string\"\n" +
            "\t\t},\n" +
            "\t\t\"lastName\": {\n" +
            "\t\t\t\"type\": \"string\"\n" +
            "\t\t}\n" +
            "\t},\n" +
            "\t\"required\": [\"firstName\", \"lastName\"]\n" +
            "}";

    protected final String INVALID_SCHEMA_MISSING_DECLARATION = "{\n" +
            "\t\"type\": \"object\",\n" +
            "\t\"properties\": {\n" +
            "\t\t\"firstName\": {\n" +
            "\t\t\t\"type\": \"string\"\n" +
            "\t\t},\n" +
            "\t\t\"lastName\": {\n" +
            "\t\t\t\"type\": \"string\"\n" +
            "\t\t}\n" +
            "\t},\n" +
            "\t\"required\": [\"firstName\", \"lastName\"]\n" +
            "}";

    protected final String INVALID_SCHEMA = "{\n" +
            "\t\"$schema\": \"http://json-schema.org/draft-04/schema#\",\n" +
            "\t\"type\": \"object\",\n" +
            "\t\"properties\": {\n" +
            "\t\t\"firstName\": {\n" +
            "\t\t\t\"type\": \"string\"\n" +
            "\t\t},\n" +
            "\t\t\"lastName\": {\n" +
            "\t\t\t\"type\": \"string\"\n" +
            "\t\t}\n" +
            "\t},\n" +
            "\t\"required\": [\"firstName\", \"lastName\"]\n" +
            "";

    protected final String INVALID_JSON_CONTENT = "{\n" +
            "\t\"firstName\": \"John\",\n" +
            "\t\"lastName\": \"Doe\"\n" +
            "";

    protected final String CONTENT_MATCHING_PERSON_SCHEMA = "{\n" +
            "\t\"firstName\": \"John\",\n" +
            "\t\"lastName\": \"Doe\"\n" +
            "}";

    protected final String CONTENT_NOT_MATCHING_PERSON_SCHEMA = "{\n" +
            "\t\"firstName\": \"John\",\n" +
            "\t\"someOtherProperty\": \"Doe\"\n" +
            "}";

    protected Logger log;

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        log = LoggerFactory.getLogger(ConfigurationResourceValidatorTest.class);
    }

    protected class PersonResourceRequest extends DummyHttpServerRequest {

        private HttpMethod method;
        private String uri;
        private String path;
        private String body;
        private HttpServerResponse response;

        public PersonResourceRequest(HttpMethod method, String uri, String path, String body, HttpServerResponse response) {
            this.method = method;
            this.uri = uri;
            this.path = path;
            this.body = body;
            this.response = response;
        }

        @Override public HttpMethod method() {
            return method;
        }

        @Override public String uri() {
            return uri;
        }

        @Override public String path() {
            return path;
        }

        @Override public MultiMap headers() { return new CaseInsensitiveHeaders(); }

        @Override public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
            bodyHandler.handle(Buffer.buffer(body));
            return this;
        }

        @Override
        public HttpServerResponse response() {
            return response;
        }
    }

    protected class TestConfigurationResourceObserver implements ConfigurationResourceObserver {

        @Override
        public void resourceChanged(String resourceUri, String resource) { }

        @Override
        public void resourceRemoved(String resourceUri) { }
    }
}
