package org.swisspush.gateleen.delegate;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;

import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests some features of the {@link DelegateHandler}.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class DelegateHandlerTest {
    private static final String DELEGATE_URI = "/gateleen/server/delegate/v1/delegates/";
    private static DelegateHandler delegateHandler;

    @BeforeClass
    public static void init() {
        delegateHandler = new DelegateHandler(null, null, null, DELEGATE_URI,
                null, null);
    }

    @Test
    public void testHandle() {
        String delegateName = "aName";
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);

        verifyNoInteractions(response);
        Assert.assertFalse(delegateHandler.handle(new CustomHttpServerRequest(DELEGATE_URI + delegateName + "/blah", response)));
        Assert.assertFalse(delegateHandler.handle(new CustomHttpServerRequest(DELEGATE_URI + delegateName, response)));
    }

    @Test
    public void testGetDelegateName_Recognition() {
        String delegateName = "aName";

        // Positive Cases
        // --------------

        // Case 1: /gateleen/server/delegate/v1/delegates/<name>
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName ));

        // Case 2: /gateleen/server/delegate/v1/delegates/<name>/
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/"));

        // Case 3: /gateleen/server/delegate/v1/delegates/<name>/execution/xxx
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/execution/xxx"));

        // Case 4: /gateleen/server/delegate/v1/delegates/<name>/execution/
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/execution/"));

        // Case 5: /gateleen/server/delegate/v1/delegates/<name>/execution
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/execution"));

        // Case 6: /gateleen/server/delegate/v1/delegates/<name>/definition
        Assert.assertEquals(delegateName, delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/definition"));

        // --------------

        // Negative Cases
        // --------------
        // Case 1: /gateleen/server/delegate/v1/delegates/<name>/blah
        Assert.assertNull(delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/blah"));

        // Case 2: /gateleen/server/delegate/v1/delegates/<name>/definition/
        Assert.assertNull(delegateHandler.getDelegateName(DELEGATE_URI + delegateName + "/definition/"));

        // --------------
    }

    private static class CustomHttpServerRequest extends DummyHttpServerRequest {

        private final String uri;
        private final HttpServerResponse response;

        public CustomHttpServerRequest(String uri, HttpServerResponse response) {
            this.uri = uri;
            this.response = response;
        }

        @Override public String uri() {
            return uri;
        }

        @Override public HttpMethod method() {
            return HttpMethod.GET;
        }

        @Override
        public HttpServerResponse response() {
            return response != null ? response : super.response();
        }

    }
}
