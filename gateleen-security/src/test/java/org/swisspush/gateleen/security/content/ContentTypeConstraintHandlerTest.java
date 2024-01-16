package org.swisspush.gateleen.security.content;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.configuration.ConfigurationResourceManager;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.security.PatternHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Test class for the {@link ContentTypeConstraintHandler}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ContentTypeConstraintHandlerTest extends ContentTypeConstraintTestBase {

    private ContentTypeConstraintHandler handler;
    private ContentTypeConstraintRepository repository;
    private ConfigurationResourceManager configurationResourceManager;
    private MockResourceStorage storage;

    private final String configResourceUri = "/gateleen/configs/contentTypeConstraints";

    private final String VALID_CONFIG = ResourcesUtils.loadResource("testresource_valid_contenttype_constraint_resource", true);
    private final String VALID_CONFIG2 = ResourcesUtils.loadResource("testresource_valid_contenttype_constraint_resource2", true);

    @Before
    public void setUp() {
        storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(Vertx.vertx(), storage);
        repository = Mockito.spy(new ContentTypeConstraintRepository());
        handler = new ContentTypeConstraintHandler(configurationResourceManager, repository, configResourceUri);
    }

    @Test
    public void initWithMissingConfigResource(TestContext context) {
        Async async = context.async();
        context.assertFalse(handler.isInitialized());
        handler.initialize().onComplete(event -> {
            verifyNoInteractions(repository);
            context.assertFalse(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void initWithExistingConfigResource(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, VALID_CONFIG);
        context.assertFalse(handler.isInitialized());
        handler.initialize().onComplete(event -> {
            List<ContentTypeConstraint> constraints = Arrays.asList(
                    createConstraint("/gateleen/contacts/zips/(.*)", Collections.singletonList("image/.*")),
                    createConstraint("/gateleen/contacts/storage/(.*)", Arrays.asList("image/png", "image/bmp", "video/mp4"))
            );
            verify(repository, times(1)).setConstraints(eq(constraints));
            context.assertTrue(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void handleWithNoConfigNoHeaderNoDefaults(TestContext context) {
        Async async = context.async();
        String requestUri = "/gateleen/constraint/tests/abc";
        handler.initialize().onComplete(event -> {

            HttpServerResponse response = spy(new ConstraintResponse());
            ConstraintRequest request = new ConstraintRequest(HttpMethod.POST, requestUri, MultiMap.caseInsensitiveMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertFalse(handled);
            verifyNoInteractions(repository);

            async.complete();
        });        
    }

    @Test
    public void handleWithNoConfigNoHeaderWithDefaults(TestContext context) {
        Async async = context.async();
        String requestUri = "/gateleen/constraint/tests/abc";

        handler = new ContentTypeConstraintHandler(configurationResourceManager, repository, configResourceUri,
                Collections.singletonList(new PatternHolder("image/png")));

        handler.initialize().onComplete(event -> {

            HttpServerResponse response = spy(new ConstraintResponse());
            ConstraintRequest request = new ConstraintRequest(HttpMethod.POST, requestUri, MultiMap.caseInsensitiveMultiMap(), response);
            final boolean handled = handler.handle(request);

            context.assertFalse(handled);
            verifyNoInteractions(repository);
            async.complete();
        });
    }

    @Test
    public void handleWithNoConfigWithHeaderMatchingDefaults(TestContext context) {
        Async async = context.async();
        String requestUri = "/gateleen/constraint/tests/abc";

        handler = new ContentTypeConstraintHandler(configurationResourceManager, repository, configResourceUri,
                Collections.singletonList(new PatternHolder("image/.*")));

        handler.initialize().onComplete(event -> {
            HttpServerResponse response = spy(new ConstraintResponse());
            ConstraintRequest request = new ConstraintRequest(HttpMethod.POST, requestUri, headersWithContentType("image/bmp"), response);
            final boolean handled = handler.handle(request);

            context.assertFalse(handled);
            verifyNoInteractions(repository);
            async.complete();
        });
    }

    @Test
    public void handleWithNoConfigWithHeaderNotMatchingDefaults(TestContext context) {
        Async async = context.async();
        String requestUri = "/gateleen/constraint/tests/abc";

        handler = new ContentTypeConstraintHandler(configurationResourceManager, repository, configResourceUri,
                Collections.singletonList(new PatternHolder("image/.*")));

        handler.initialize().onComplete(event -> {
            HttpServerResponse response = spy(new ConstraintResponse());
            ConstraintRequest request = new ConstraintRequest(HttpMethod.POST, requestUri, headersWithContentType("video/mp4"), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verify(repository, times(1)).findMatchingContentTypeConstraint(eq(requestUri));
            verifyUnsupportedMediaType(response);
            async.complete();
        });
    }

    @Test
    public void handleNotMatchingAnyConstraint(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, VALID_CONFIG);
        context.assertFalse(handler.isInitialized());
        String requestUri = "/gateleen/constraint/tests/abc";
        handler.initialize().onComplete(event -> {

            HttpServerResponse response = spy(new ConstraintResponse());
            ConstraintRequest request = new ConstraintRequest(HttpMethod.POST, requestUri, headersWithContentType("image/bmp"), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verify(repository, times(1)).findMatchingContentTypeConstraint(eq(requestUri));
            verifyUnsupportedMediaType(response);
            async.complete();
        });
    }

    @Test
    public void handleMatchingConstraintWithNotAllowedContentTypeHeader(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, VALID_CONFIG);
        context.assertFalse(handler.isInitialized());
        String requestUri = "/gateleen/contacts/storage/abc";
        handler.initialize().onComplete(event -> {

            HttpServerResponse response = spy(new ConstraintResponse());
            ConstraintRequest request = new ConstraintRequest(HttpMethod.POST, requestUri, headersWithContentType("application/json"), response);
            final boolean handled = handler.handle(request);

            context.assertTrue(handled);
            verify(repository, times(1)).findMatchingContentTypeConstraint(eq(requestUri));
            verifyUnsupportedMediaType(response);
            async.complete();
        });
    }

    @Test
    public void handleMatchingConstraintWithAllowedContentTypeHeader(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, VALID_CONFIG);
        context.assertFalse(handler.isInitialized());
        String requestUri = "/gateleen/contacts/storage/abc";
        handler.initialize().onComplete(event -> {

            HttpServerResponse response = spy(new ConstraintResponse());
            ConstraintRequest request = new ConstraintRequest(HttpMethod.POST, requestUri, headersWithContentType("video/mp4"), response);
            final boolean handled = handler.handle(request);

            context.assertFalse(handled);
            verify(repository, times(1)).findMatchingContentTypeConstraint(eq(requestUri));

            async.complete();
        });
    }

    @Test
    public void handleMatchingConstraintWithAllowedContentTypeHeaderWithCharset(TestContext context) {
        Async async = context.async();
        storage.putMockData(configResourceUri, VALID_CONFIG);
        context.assertFalse(handler.isInitialized());
        String requestUri = "/gateleen/contacts/storage/abc";
        handler.initialize().onComplete(event -> {

            HttpServerResponse response = spy(new ConstraintResponse());
            ConstraintRequest request = new ConstraintRequest(HttpMethod.POST, requestUri, headersWithContentType("video/mp4;charset=UTF-8"), response);
            final boolean handled = handler.handle(request);

            context.assertFalse(handled);
            verify(repository, times(1)).findMatchingContentTypeConstraint(eq(requestUri));

            async.complete();
        });
    }

    @Test
    public void resourceRemovedMatchingUri(TestContext context){
        Async async = context.async();
        storage.putMockData(configResourceUri, VALID_CONFIG);
        context.assertFalse(handler.isInitialized());
        handler.initialize().onComplete(event -> {
            reset(repository); // reset the calls from initialization
            context.assertTrue(handler.isInitialized());

            handler.resourceRemoved(configResourceUri);
            verify(repository, times(1)).clearConstraints();
            context.assertFalse(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void resourceRemovedNotMatchingUri(TestContext context){
        Async async = context.async();
        storage.putMockData(configResourceUri, VALID_CONFIG);
        context.assertFalse(handler.isInitialized());
        handler.initialize().onComplete(event -> {
            reset(repository); // reset the calls from initialization
            context.assertTrue(handler.isInitialized());

            handler.resourceRemoved("/some/other/uri");
            verifyNoInteractions(repository);
            context.assertTrue(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void resourceChangedNotMatchingUri(TestContext context){
        Async async = context.async();
        storage.putMockData(configResourceUri, VALID_CONFIG);
        context.assertFalse(handler.isInitialized());
        handler.initialize().onComplete(event -> {
            reset(repository); // reset the calls from initialization
            context.assertTrue(handler.isInitialized());

            handler.resourceChanged("/some/other/uri", Buffer.buffer(VALID_CONFIG2));
            verifyNoInteractions(repository);
            context.assertTrue(handler.isInitialized());
            async.complete();
        });
    }

    @Test
    public void resourceChangedMatchingUri(TestContext context){
        Async async = context.async();
        storage.putMockData(configResourceUri, VALID_CONFIG);
        context.assertFalse(handler.isInitialized());
        handler.initialize().onComplete(event -> {
            reset(repository); // reset the calls from initialization
            context.assertTrue(handler.isInitialized());

            handler.resourceChanged(configResourceUri, Buffer.buffer(VALID_CONFIG2));

            List<ContentTypeConstraint> constraints = Collections.singletonList(
                    createConstraint("/gateleen/images/(.*)", Collections.singletonList("image/.*"))
            );
            verify(repository, times(1)).setConstraints(eq(constraints));

            context.assertTrue(handler.isInitialized());
            async.complete();
        });
    }

    private void verifyUnsupportedMediaType(HttpServerResponse response){
        verify(response, times(1)).setStatusCode(eq(StatusCode.UNSUPPORTED_MEDIA_TYPE.getStatusCode()));
    }

    private MultiMap headersWithContentType(String contentType){
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("Content-Type", contentType);
        return headers;
    }

    static class ConstraintRequest extends DummyHttpServerRequest {
        private final String uri;
        private final HttpMethod method;
        private final MultiMap headers;
        private final HttpServerResponse response;

        ConstraintRequest(HttpMethod method, String uri, MultiMap headers, HttpServerResponse response) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.response = response;
        }

        @Override public HttpMethod method() {
            return method;
        }
        @Override public String uri() {
            return uri;
        }
        @Override public MultiMap headers() { return headers; }

        @Override public HttpServerResponse response() { return response; }
    }

    static class ConstraintResponse extends DummyHttpServerResponse {

        private final MultiMap headers;

        ConstraintResponse(){
            this.headers = MultiMap.caseInsensitiveMultiMap();
        }

        @Override public MultiMap headers() { return headers; }
    }    
}
