package org.swisspush.gateleen.security.authorization;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;
import org.swisspush.gateleen.core.http.DummyHttpServerResponse;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.timeout;

/**
 * Tests for the {@link Authorizer} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class AuthorizerTest {

    private Authorizer authorizer;
    private Vertx vertx;
    private MockResourceStorage storage;

    private static final String ROLE_PATTERN = "^z-gateleen[-_](.*)$";
    private static final String ACLS = "/gateleen/server/security/v1/acls/";
    private static final String ACLS_DIR = "acls/";
    private static final String ROLEMAPPER = "/gateleen/server/security/v1/rolemapper";
    private static final String ROLEMAPPER_DIR = "rolemapper/";

    @Rule
    public Timeout rule = Timeout.seconds(5);

    @Before
    public void setUp(){
        vertx = Vertx.vertx();
        storage = new MockResourceStorage();
        setupAcls();
        authorizer = new Authorizer(vertx, storage, "/gateleen/server/security/v1/", ROLE_PATTERN);
    }

    @Test
    public void testAuthorizeUserUriGETRequest(TestContext context) {
        String requestUri = "/gateleen/server/security/v1/user";

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add("x-rp-usr", "user_1234");
        headers.add("x-rp-grp", "z-gateleen-admin,z-gateleen-authenticated,z-gateleen-developer");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result()); // false means that the request must not be handled anymore
        });

        Mockito.verify(response, timeout(1000).times(1))
                .end(eq("{\"userId\":\"user_1234\",\"roles\":[\"authenticated\",\"everyone\",\"admin\",\"developer\"]}"));
    }

    @Test
    public void testAuthorizeUserUriGETRequestWithCASName(TestContext context) {
        String requestUri = "/gateleen/server/security/v1/user";

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add("cas_name", "cas_user_1234");
        headers.add("x-rp-grp", "z-gateleen-admin,z-gateleen-authenticated,z-gateleen-developer");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result()); // false means that the request must not be handled anymore
        });

        Mockito.verify(response, timeout(1000).times(1))
                .end(eq("{\"userId\":\"cas_user_1234\",\"roles\":[\"authenticated\",\"everyone\",\"admin\",\"developer\"]}"));
    }

    @Test
    public void testAuthorizeUserUriDELETERequest(TestContext context) {
        String requestUri = "/gateleen/server/security/v1/user";

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add("x-rp-usr", "user_1234");
        headers.add("x-rp-grp", "z-gateleen-admin,z-gateleen-authenticated,z-gateleen-developer");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.DELETE, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result()); // false means that the request must not be handled anymore
        });

        Mockito.verify(response, timeout(1000).times(1)).setStatusCode(eq(StatusCode.METHOD_NOT_ALLOWED.getStatusCode()));
        Mockito.verify(response, timeout(1000).times(1)).setStatusMessage(eq(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage()));
        Mockito.verify(response, timeout(1000).times(1)).end();
    }

    @Test
    public void testAuthorizeUserUriPUTRequest(TestContext context) {
        String requestUri = "/gateleen/server/security/v1/user";

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add("x-rp-usr", "user_1234");
        headers.add("x-rp-grp", "z-gateleen-admin,z-gateleen-authenticated,z-gateleen-developer");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result()); // false means that the request must not be handled anymore
        });

        Mockito.verify(response, timeout(1000).times(1)).setStatusCode(eq(StatusCode.METHOD_NOT_ALLOWED.getStatusCode()));
        Mockito.verify(response, timeout(1000).times(1)).setStatusMessage(eq(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage()));
        Mockito.verify(response, timeout(1000).times(1)).end();
    }

    @Test
    public void testAuthorizeAclUriGETRequest(TestContext context) {
        String requestUri = "/gateleen/server/security/v1/acls/admin";

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, new CaseInsensitiveHeaders(), response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
        });

        Mockito.verifyZeroInteractions(response);
    }

    @Test
    public void testAuthorizeAclUriDELETERequest(TestContext context) {
        String requestUri = "/gateleen/server/security/v1/acls/admin";

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.DELETE, requestUri, new CaseInsensitiveHeaders(), response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result());
        });

        Mockito.verify(response, timeout(1000).times(1)).end();
    }

    @Test
    public void testAuthorizeAclUriPUTRequest(TestContext context) {
        String requestUri = "/gateleen/server/security/v1/acls/admin";

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, new CaseInsensitiveHeaders(),
                "{}", response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result());
        });

        Mockito.verify(response, timeout(1000).times(1)).end();
    }

    @Test
    public void testAuthorizeAclUriPUTRequestInvalid(TestContext context) {
        String requestUri = "/gateleen/server/security/v1/acls/admin";

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, new CaseInsensitiveHeaders(),
                "{invalidJson}", response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result());
        });

        Mockito.verify(response, timeout(1000).times(1)).setStatusCode(eq(StatusCode.BAD_REQUEST.getStatusCode()));
        Mockito.verify(response, timeout(1000).times(1)).setStatusMessage(eq("Bad Request Unable to parse json"));
        Mockito.verify(response, timeout(1000).times(1)).end("Unable to parse json");
    }

    @Test
    public void testHandleIsAuthorized(TestContext context) {
        String requestUri = "/gateleen/server/tests/someResource";

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add("x-rp-grp", "z-gateleen-admin,z-gateleen-authenticated,z-gateleen-developer");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
        });

        Mockito.verifyZeroInteractions(response);
    }

    @Test
    public void testHandleNotIsAuthorized(TestContext context) {
        String requestUri = "/gateleen/server/tests/someResource";

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        headers.add("x-rp-grp", "z-gateleen-authenticated,z-gateleen-developer");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result()); // false means that the request must not be handled anymore
        });

        Mockito.verify(response, timeout(1000).times(1)).setStatusCode(eq(StatusCode.FORBIDDEN.getStatusCode()));
        Mockito.verify(response, timeout(1000).times(1)).setStatusMessage(eq(StatusCode.FORBIDDEN.getStatusMessage()));
        Mockito.verify(response, timeout(1000).times(1)).end(eq(StatusCode.FORBIDDEN.getStatusMessage()));
    }


    @Test
    public void testHandleAclRoleMapper(TestContext context) {
        String requestUri = "/gateleen/domain/tests/someResource";

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
        // we have only a acl for "domain" which must trigger in this case as well
        // see https://github.com/swisspush/gateleen/issues/285
        headers.add("x-rp-grp", "z-gateleen-domain1-admin");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
        });
        Mockito.verifyZeroInteractions(response);

    }


    private void setupAcls(){
        JsonObject acls = new JsonObject();
        acls.put("acls", new JsonArray(Arrays.asList("admin", "authenticated", "developer", "user", "domain")));

        storage.putMockData(ACLS, acls.encode());

        storage.putMockData(ACLS + "admin", ResourcesUtils.loadResource(ACLS_DIR + "admin", true));
        storage.putMockData(ACLS + "authenticated", ResourcesUtils.loadResource(ACLS_DIR + "authenticated", true));
        storage.putMockData(ACLS + "developer", ResourcesUtils.loadResource(ACLS_DIR + "developer", true));
        storage.putMockData(ACLS + "user", ResourcesUtils.loadResource(ACLS_DIR + "user", true));
        storage.putMockData(ACLS + "domain", ResourcesUtils.loadResource(ACLS_DIR + "domain", true));

        storage.putMockData(ROLEMAPPER, ResourcesUtils.loadResource(ROLEMAPPER_DIR + "rolemapper", true));
    }

    class AuthorizerRequest extends DummyHttpServerRequest {
        private String uri;
        private HttpMethod method;
        private String body;
        private CaseInsensitiveHeaders headers;
        private HttpServerResponse response;

        public AuthorizerRequest(HttpMethod method, String uri, CaseInsensitiveHeaders headers,
                                 HttpServerResponse response) {
            this(method, uri, headers, "", response);
        }

        public AuthorizerRequest(HttpMethod method, String uri, CaseInsensitiveHeaders headers,
                                 String body, HttpServerResponse response) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            this.response = response;
        }

        @Override public HttpMethod method() { return method; }
        @Override public String uri() { return uri; }
        @Override public MultiMap headers() { return headers; }
        @Override public HttpServerResponse response() { return response; }

        @Override
        public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
            bodyHandler.handle(Buffer.buffer(body));
            return this;
        }
    }
}