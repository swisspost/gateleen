package org.swisspush.gateleen.validation.validation;

import org.swisspush.gateleen.validation.validation.mocks.HttpServerRequestMock;
import org.swisspush.gateleen.validation.validation.mocks.ResourceStorageMock;
import com.google.common.util.concurrent.SettableFuture;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class ValidatorTest {

    private ResourceStorageMock storage;
    private final String SCHEMA_ROOT = "/foo/schemas/apis/";
    private Validator validator;

    @Before
    public void setUp(){
        storage = new ResourceStorageMock();
        validator = new Validator(storage, SCHEMA_ROOT);

        storage.putMockData("/foo/schemas/apis/","{\"apis\": [\"foo\"]}");
    }


    @Test
    public void testValidationWithVariables(TestContext context){
        // add Data for lowdash replacement
        storage.putMockData("/foo/schemas/apis/foo/","{\"foo\": [\"mediadata\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/","{\"mediadata\": [\"v1\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/","{\"v1\": [\"specials\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/specials/","{\"specials\": [\"_\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/specials/_/","{\"_\": [\"GET\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/specials/_/GET/","{\"GET\": [\"out\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediadata/v1/specials/_/GET/out","{\n" +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\",    \n" +
                "    \"type\": \"object\"\n" +
                "}");

        class GETValidationResourceRequest extends HttpServerRequestMock {
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }
            @Override public String uri() { return "/foo/mediadata/v1/specials/03";   }
            @Override public String path() {
                return "/foo/mediadata/v1/specials/03";
            }
        }

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer();
        final SettableFuture<String> future = SettableFuture.create();
        validator.validate(new GETValidationResourceRequest(), type, jsonBuffer, validationResult -> {
            String message = validationResult.getMessage();
            context.assertFalse(message.contains("Could not get path"), message);
            context.assertFalse(message.contains("No schema for"), message);
            context.assertTrue(message.contains("Cannot read JSON"), message);// No correct mock schema - but this means it found it
            future.set(message);
        });
        context.assertTrue(future.isDone());
    }

    @Test
    public void testValidation(TestContext context){
        // add Data for lowdash replacement
        storage.putMockData("/foo/schemas/apis/foo/","{\"foo\": [\"mediamessage\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/","{\"mediamessage\": [\"v1\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/","{\"v1\": [\"output\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/","{\"output\": [\"front\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/front/","{\"front\": [\"GET\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/front/GET/","{\"GET\": [\"out\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/front/GET/out","{\n" +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\",    \n" +
                "    \"type\": \"object\"\n" +
                "}");

        class GETValidationResourceRequest extends HttpServerRequestMock {
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }
            @Override public String uri() { return "/foo/mediamessage/v1/output/front";   }
            @Override public String path() {
                return "/foo/mediamessage/v1/output/front";
            }
        }

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer();
        final SettableFuture<String> future = SettableFuture.create();
        validator.validate(new GETValidationResourceRequest(), type, jsonBuffer, validationResult -> {
            String message = validationResult.getMessage();
            context.assertFalse(message.contains("Could not get path"), message);
            context.assertFalse(message.contains("No schema for"), message);
            context.assertTrue(message.contains("Cannot read JSON"), message);// No correct mock schema - but this means it found it
            future.set(message);
        });
        context.assertTrue(future.isDone());
    }

    @Test
    public void testValidationWithNoSchema(TestContext context){
        // add Data for lowdash replacement
        storage.putMockData("/foo/schemas/apis/foo/","{\"foo\": [\"mediamessage\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/","{\"mediamessage\": [\"v1\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/","{\"v1\": [\"output\"]}");
        storage.putMockData("/foo/schemas/apis/foo/mediamessage/v1/output/","{\"output\": [\"front\"]}");
        // schema line missing

        class GETValidationResourceRequest extends HttpServerRequestMock {
            @Override public HttpMethod method() {
                return HttpMethod.GET;
            }
            @Override public String uri() { return "/foo/mediamessage/v1/output/front";   }
            @Override public String path() {
                return "/foo/mediamessage/v1/output/front";
            }
        }

        String type = "GET/out";
        Buffer jsonBuffer = Buffer.buffer();
        final SettableFuture<String> future = SettableFuture.create();
        validator.validate(new GETValidationResourceRequest(), type, jsonBuffer, validationResult -> {
            String message = validationResult.getMessage();
            context.assertTrue(message.contains("Could not get path"), message);
            future.set(message);
        });
        context.assertTrue(future.isDone());
    }
}
