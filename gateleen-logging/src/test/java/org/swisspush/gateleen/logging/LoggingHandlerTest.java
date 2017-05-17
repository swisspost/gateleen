package org.swisspush.gateleen.logging;

import com.google.common.collect.ImmutableMap;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import java.util.List;
import java.util.Map;

/**
 * Tests for the {@link LoggingHandler} class
 *
 * @author Dominik Erbsland
 */
@RunWith(VertxUnitRunner.class)
public class LoggingHandlerTest {

    private static final String TYPE = "type";
    private static final String FILE = "file";
    private static final String METADATA = "metadata";
    private static final String TRANSMISSION = "transmission";
    private static final String ADDRESS = "address";
    private static final String EVENT_BUS = "eventBus";
    private Vertx vertx;
    private ResourceStorage storage;

    private final String LOGGING_URI = "/playground/server/admin/v1/logging";
    private final String GET_REQUEST_URI = "/playground/server/users/v1/test";

    private final String TEST_LOGGING_RESOURCE = ResourcesUtils.loadResource("testresource_logging_handler_test", true);

    @Before
    public void setUp() {
        vertx = Mockito.mock(Vertx.class);
        Mockito.when(vertx.eventBus()).thenReturn(Mockito.mock(EventBus.class));
        storage = new MockResourceStorage(ImmutableMap.of(LOGGING_URI, TEST_LOGGING_RESOURCE));
    }

    @Test
    public void testCustomSorting(TestContext context) {
        LoggingResourceManager manager = new LoggingResourceManager(vertx, storage, LOGGING_URI);
        LoggingResource loggingResource = manager.getLoggingResource();

        // PayloadFilters
        List<Map<String, String>> payloadFilterEntries = loggingResource.getPayloadFilters();
        context.assertEquals(2, payloadFilterEntries.size());

        // Check that the Resource has the correct sequence of the resource entries.
        context.assertEquals(payloadFilterEntries.get(0).get("url"), "/playground/nsa/v1/acknowledgment/.*");
        context.assertEquals(payloadFilterEntries.get(1).get("url"), "/playground/server/users/v1/.*");

        GETRequest request = new GETRequest();

        LoggingHandler loggingHandler = new LoggingHandler(manager, request, vertx.eventBus());

        // Check whether "active" is set to TRUE, which means the Logging for the GET Request
        // is happening and is not aborted (which was the case before the fix (NEMO-5551))
        context.assertTrue(loggingHandler.isActive());

        // Switch the entries. The test must also pass/true if correct.
        Map<String, String> firstEntry = payloadFilterEntries.get(0);
        Map<String, String> secondEntry = payloadFilterEntries.get(1);

        payloadFilterEntries.set(0,secondEntry);
        payloadFilterEntries.set(1,firstEntry);

        context.assertTrue(loggingHandler.isActive());
    }

    class GETRequest extends DummyHttpServerRequest {
        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();

        @Override
        public HttpMethod method() {
            return HttpMethod.GET;
        }

        @Override
        public String uri() {
            return GET_REQUEST_URI;
        }

        @Override
        public MultiMap headers() {
            return headers;
        }

        public void addHeader(String headerName, String headerValue) {
            headers.add(headerName, headerValue);
        }
    }
}
