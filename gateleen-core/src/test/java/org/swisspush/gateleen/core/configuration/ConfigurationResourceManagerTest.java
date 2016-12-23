package org.swisspush.gateleen.core.configuration;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.storage.MockResourceStorage;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link ConfigurationResourceManager} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class ConfigurationResourceManagerTest extends ConfigurationResourceTestBase {

    private ConfigurationResourceManager configurationResourceManager;

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

        verify(observer, Mockito.times(1)).resourceChanged(eq(resourceURI), eq(CONTENT_MATCHING_PERSON_SCHEMA));
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

        verify(observer, Mockito.never()).resourceChanged(Matchers.anyString(), Matchers.anyString());
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
                verify(observer, Mockito.times(1)).resourceChanged(eq(resourceURI), eq(CONTENT_MATCHING_PERSON_SCHEMA));
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
                verify(observer, Mockito.times(1)).resourceChanged(eq(resourceURI), eq(CONTENT_MATCHING_PERSON_SCHEMA));
                verify(observer2, Mockito.times(1)).resourceChanged(eq(resourceURI), eq(CONTENT_MATCHING_PERSON_SCHEMA));
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

        verify(observer, Mockito.times(1)).resourceChanged(eq(resourceURI), eq(CONTENT_MATCHING_PERSON_SCHEMA));

        PersonResourceRequest deleteRequest = new PersonResourceRequest(HttpMethod.DELETE, resourceURI,
                resourceURI, null, new DummyHttpServerResponse());

        boolean handledDelete = configurationResourceManager.handleConfigurationResource(deleteRequest);
        context.assertTrue(handledDelete, "DELETE Request to configuration resource should be handled");

        await().atMost(3, SECONDS).until( () -> storage.getMockData().get(resourceURI), is(nullValue()));
        verify(observer, Mockito.times(1)).resourceRemoved(eq(resourceURI));

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
