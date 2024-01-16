package org.swisspush.gateleen.core.configuration;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.storage.MockResourceStorage;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.swisspush.gateleen.core.configuration.ConfigurationResourceManager.CONFIG_RESOURCE_CHANGED_ADDRESS;

/**
 * Tests for the {@link ConfigurationResourceManager} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ConfigurationResourceManagerTest extends ConfigurationResourceTestBase {

    private ConfigurationResourceManager configurationResourceManager;

    @Test
    public void testGetRegisteredResourceNotYetRegistered(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        configurationResourceManager.getRegisteredResource(resourceURI).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result().isPresent());
            async.complete();
        });
    }

    @Test
    public void testGetRegisteredResource(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        PersonResourceRequest request = new PersonResourceRequest(HttpMethod.PUT, resourceURI,
                resourceURI, CONTENT_MATCHING_PERSON_SCHEMA, new DummyHttpServerResponse());

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        boolean handled = configurationResourceManager.handleConfigurationResource(request);
        context.assertTrue(handled, "PUT Request to configuration resource should be handled");

        // resource should be in storage
        await().atMost(3, SECONDS).until( () -> storage.getMockData().get(resourceURI), equalTo(CONTENT_MATCHING_PERSON_SCHEMA));

        configurationResourceManager.getRegisteredResource(resourceURI).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result().isPresent());
            context.assertEquals(CONTENT_MATCHING_PERSON_SCHEMA, event.result().get().toString());
            async.complete();
        });
    }

    @Test
    public void testGetRegisteredResourceInitiallyLoadedFromStorage(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        // add mock data to storage
        storage.putMockData(resourceURI, CONTENT_MATCHING_PERSON_SCHEMA);

        // resource should not be in storage
        context.assertTrue(storage.getMockData().containsKey(resourceURI));

        configurationResourceManager.getRegisteredResource(resourceURI).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result().isPresent());
            context.assertEquals(CONTENT_MATCHING_PERSON_SCHEMA, event.result().get().toString());
            async.complete();
        });
    }

    @Test
    public void testGetRegisteredResourceInitiallyLoadedFromStorageInvalid(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        // add invalid mock data to storage
        storage.putMockData(resourceURI, CONTENT_NOT_MATCHING_PERSON_SCHEMA);

        // resource should not be in storage
        context.assertTrue(storage.getMockData().containsKey(resourceURI));

        configurationResourceManager.getRegisteredResource(resourceURI).onComplete(event -> {
            context.assertTrue(event.failed());
            context.assertTrue(event.cause().getMessage().contains("Validation failed"));
            async.complete();
        });
    }

    @Test
    public void testRegistrationAndValidUpdateWithSchema(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        PersonResourceRequest request = new PersonResourceRequest(HttpMethod.PUT, resourceURI,
                resourceURI, CONTENT_MATCHING_PERSON_SCHEMA, new DummyHttpServerResponse());

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        boolean handled = configurationResourceManager.handleConfigurationResource(request);
        context.assertTrue(handled, "PUT Request to configuration resource should be handled");

        // resource should be in storage
        await().atMost(3, SECONDS).until( () -> storage.getMockData().get(resourceURI), equalTo(CONTENT_MATCHING_PERSON_SCHEMA));

        verify(observer, timeout(1000).times(1)).resourceChanged(eq(resourceURI), eq(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA)));
        async.complete();
    }

    @Test
    public void testRegistrationAndInvalidUpdateWithSchema(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        PersonResourceRequest request = new PersonResourceRequest(HttpMethod.PUT, resourceURI,
                resourceURI, CONTENT_NOT_MATCHING_PERSON_SCHEMA, new DummyHttpServerResponse());

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        boolean handled = configurationResourceManager.handleConfigurationResource(request);
        context.assertTrue(handled, "PUT Request to configuration resource should be handled");

        // resource should still not be in storage after invalid update
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        verify(observer, Mockito.never()).resourceChanged(anyString(), any(Buffer.class));
        async.complete();
    }

    @Test
    public void testNoNotificationAfterUnsuccessfulStoragePut(TestContext context) throws InterruptedException {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();

        int storagePutFailStatusCode = 400;

        storage.failPutWith(storagePutFailStatusCode);
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        DummyHttpServerResponse response = new DummyHttpServerResponse();
        PersonResourceRequest request = new PersonResourceRequest(HttpMethod.PUT, resourceURI,
                resourceURI, CONTENT_MATCHING_PERSON_SCHEMA, response);

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        boolean handled = configurationResourceManager.handleConfigurationResource(request);
        context.assertTrue(handled, "PUT Request to configuration resource should be handled");

        // resource should still not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        Thread.sleep(1000);

        context.assertEquals(storagePutFailStatusCode, response.getStatusCode());

        verify(observer, Mockito.never()).resourceChanged(anyString(), any(Buffer.class));
        async.complete();
    }

    @Test
    public void testNotSupportedConfigurationResourceChangeType(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = Mockito.spy(new MockResourceStorage());
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        // simulate an event bus publish which would have been made after storing the resource in the storage
        JsonObject object = new JsonObject();
        object.put("requestUri", resourceURI);
        object.put("type", "NOT_SUPPORTED_TYPE");
        vertx.eventBus().publish(CONFIG_RESOURCE_CHANGED_ADDRESS, object);

        // only 1 occurence of Storage.get is allowed during registerObserver. The second call would have come after the publish
        verify(storage, times(1)).get(anyString(), any());

        verify(observer, Mockito.never()).resourceChanged(anyString(), any(Buffer.class));
        async.complete();
    }

    @Test
    public void testRequestWithoutUri(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        PersonResourceRequest request = new PersonResourceRequest(HttpMethod.PUT, null,
                null, null, new DummyHttpServerResponse());

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        boolean handled = configurationResourceManager.handleConfigurationResource(request);
        context.assertFalse(handled, "PUT Request without uri should not have been handled");

        // resource should still not be in storage
        await().atMost(3, SECONDS).until( () -> storage.getMockData().get(resourceURI), nullValue());

        async.complete();
    }

    @Test
    public void testGETRequestToRegisteredResourceUriShouldNotBeHandled(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        PersonResourceRequest request = new PersonResourceRequest(HttpMethod.GET, resourceURI,
                resourceURI, CONTENT_MATCHING_PERSON_SCHEMA, new DummyHttpServerResponse());

        boolean handled = configurationResourceManager.handleConfigurationResource(request);
        context.assertFalse(handled, "GET Request to configuration resource should not be handled");

        async.complete();
    }

    @Test
    public void testNotificationAfterRegistration(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";
        storage.putMockData(resourceURI, CONTENT_MATCHING_PERSON_SCHEMA);
        context.assertTrue(storage.getMockData().containsKey(resourceURI));

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);
        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        await().atMost(3, SECONDS).until(() -> {
            try {
                verify(observer, times(1)).resourceChanged(eq(resourceURI), eq(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA)));
                return true;
            }
            catch(AssertionError ae) {
                return false;
            }
        });

        async.complete();
    }

    @Test
    public void testNotificationMultipleObserversAfterRegistration(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";
        storage.putMockData(resourceURI, CONTENT_MATCHING_PERSON_SCHEMA);
        context.assertTrue(storage.getMockData().containsKey(resourceURI));

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);
        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());
        ConfigurationResourceObserver observer2 = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);
        configurationResourceManager.registerObserver(observer2, resourceURI);

        await().atMost(3, SECONDS).until(() -> {
            try {
                verify(observer, times(1)).resourceChanged(eq(resourceURI), eq(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA)));
                verify(observer2, times(1)).resourceChanged(eq(resourceURI), eq(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA)));
                return true;
            }
            catch(AssertionError ae) {
                return false;
            }
        });

        async.complete();
    }

    @Test
    public void testRemoveResource(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        PersonResourceRequest putRequest = new PersonResourceRequest(HttpMethod.PUT, resourceURI,
                resourceURI, CONTENT_MATCHING_PERSON_SCHEMA, new DummyHttpServerResponse());

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        boolean handled = configurationResourceManager.handleConfigurationResource(putRequest);
        context.assertTrue(handled, "PUT Request to configuration resource should be handled");

        // resource should be in storage
        await().atMost(3, SECONDS).until( () -> storage.getMockData().get(resourceURI), equalTo(CONTENT_MATCHING_PERSON_SCHEMA));

        verify(observer, times(1)).resourceChanged(eq(resourceURI), eq(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA)));

        PersonResourceRequest deleteRequest = new PersonResourceRequest(HttpMethod.DELETE, resourceURI,
                resourceURI, null, new DummyHttpServerResponse());

        boolean handledDelete = configurationResourceManager.handleConfigurationResource(deleteRequest);
        context.assertTrue(handledDelete, "DELETE Request to configuration resource should be handled");

        await().atMost(3, SECONDS).until( () -> storage.getMockData().get(resourceURI), is(nullValue()));
        verify(observer, times(1)).resourceRemoved(eq(resourceURI));

        async.complete();
    }

    @Test
    public void testNoNotificationWhenRemovingNotExistingResource(TestContext context) throws InterruptedException {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();

        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        String resourceURI = "/gateleen/resources/person";

        configurationResourceManager.registerResource(resourceURI, PERSON_SCHEMA);

        ConfigurationResourceObserver observer = Mockito.spy(new TestConfigurationResourceObserver());

        configurationResourceManager.registerObserver(observer, resourceURI);

        PersonResourceRequest putRequest = new PersonResourceRequest(HttpMethod.PUT, resourceURI,
                resourceURI, CONTENT_MATCHING_PERSON_SCHEMA, new DummyHttpServerResponse());

        // resource should not be in storage
        context.assertFalse(storage.getMockData().containsKey(resourceURI));

        boolean handled = configurationResourceManager.handleConfigurationResource(putRequest);
        context.assertTrue(handled, "PUT Request to configuration resource should be handled");

        // resource should be in storage
        await().atMost(3, SECONDS).until( () -> storage.getMockData().get(resourceURI), equalTo(CONTENT_MATCHING_PERSON_SCHEMA));

        verify(observer, times(1)).resourceChanged(eq(resourceURI), eq(Buffer.buffer(CONTENT_MATCHING_PERSON_SCHEMA)));

        DummyHttpServerResponse response = new DummyHttpServerResponse();
        PersonResourceRequest deleteRequest = new PersonResourceRequest(HttpMethod.DELETE, resourceURI,
                resourceURI, null, response);

        int deleteRequestFailValue = 403;
        storage.failDeleteWith(deleteRequestFailValue);

        boolean handledDelete = configurationResourceManager.handleConfigurationResource(deleteRequest);
        context.assertTrue(handledDelete, "DELETE Request to configuration resource should be handled");

        Thread.sleep(1000);

        verify(observer, Mockito.never()).resourceRemoved(eq(resourceURI));

        context.assertEquals(deleteRequestFailValue, response.getStatusCode());

        async.complete();
    }

    @Test
    public void testRequestToNotRegisteredResourceUriShouldNotBeHandled(TestContext context) {
        Async async = context.async();
        MockResourceStorage storage = new MockResourceStorage();
        configurationResourceManager = new ConfigurationResourceManager(vertx, storage);

        configurationResourceManager.registerResource("/gateleen/resources/person/abc", PERSON_SCHEMA);
        configurationResourceManager.registerResource("/gateleen/resources/person/def", PERSON_SCHEMA);
        configurationResourceManager.registerResource("/gateleen/resources/person/ghi", PERSON_SCHEMA);

        PersonResourceRequest request = new PersonResourceRequest(HttpMethod.PUT, "/some/other/resource",
                "/some/other/resource", CONTENT_MATCHING_PERSON_SCHEMA, new DummyHttpServerResponse());

        boolean handled = configurationResourceManager.handleConfigurationResource(request);
        context.assertFalse(handled, "PUT Request to some other resource should not be handled");

        async.complete();
    }
}
