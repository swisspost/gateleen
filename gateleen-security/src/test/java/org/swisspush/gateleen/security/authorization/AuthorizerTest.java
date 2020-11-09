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
import org.swisspush.gateleen.core.util.RoleExtractor;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    private CaseInsensitiveHeaders headers;

    private static final String ROLE_PATTERN = "^z-gateleen[-_](.*)$";
    private static final String ROLE_PREFIX = "z-gateleen-";
    private static final String ACLS = "/gateleen/server/security/v1/acls/";
    private static final String ACLS_DIR = "acls/";
    private static final String ROLEMAPPER = "/gateleen/server/security/v1/rolemapper";
    private static final String ROLEMAPPER_DIR = "rolemapper/";

    @Rule
    public Timeout rule = Timeout.seconds(5);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        storage = new MockResourceStorage();
        setupAcls();
        Map<String, Object> properties = new HashMap<>();
        properties.put("STAGE", "int");
        authorizer = new Authorizer(vertx, storage, "/gateleen/server/security/v1/", ROLE_PATTERN, ROLE_PREFIX, properties);
        headers = new CaseInsensitiveHeaders();
    }

    @Test
    public void testAuthorizeUserUriGETRequest(TestContext context) {
        String requestUri = "/gateleen/server/security/v1/user";

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
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, headers, response);

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
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.DELETE, requestUri, headers, response);

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
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, headers,
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
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, headers,
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

        headers.add("x-rp-grp", "z-gateleen-authenticated,z-gateleen-developer");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result()); // false means that the request must not be handled anymore
        });

        assertForbidden(response);
    }


    @Test
    public void testHandleAclRoleMapper(TestContext context) {
        String requestUri = "/gateleen/domain/tests/someResource";

        // we have only a acl for "domain" which must trigger in this case as well
        // see https://github.com/swisspush/gateleen/issues/285
        headers.add("x-rp-grp", "z-gateleen-domain-admin-stage-int");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.PUT, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            Set<String> roles = new RoleExtractor().extractRoles(req);
            context.assertTrue(roles.size() == 3);
            context.assertTrue(roles.contains("z-gateleen-domain-admin-stage-int"));
            context.assertTrue(roles.contains("z-gateleen-domain-admin-int"));
            context.assertTrue(roles.contains("z-gateleen-everyone"));
        });

        Mockito.verifyZeroInteractions(response);

    }

    @Test
    public void adminIsAuthorizedToReadAllUserspecificResourcesWithoutUserHeader(TestContext context) {
        String requestUri = "/resources/userspecific/batman/info";

        headers.add("x-rp-grp", "z-gateleen-admin,z-gateleen-authenticated");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
        });

        Mockito.verifyZeroInteractions(response);
    }

    @Test
    public void adminIsAuthorizedToReadAllUserspecificResourcesWithUserHeader(TestContext context) {
        String requestUri = "/resources/userspecific/batman/info";

        headers.add("x-user", "superman");
        headers.add("x-rp-grp", "z-gateleen-admin,z-gateleen-authenticated");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
        });

        Mockito.verifyZeroInteractions(response);
    }

    @Test
    public void userIsAuthorizedToReadItsOwnResources(TestContext context) {
        String requestUri = "/resources/userspecific/batman/info";

        headers.add("x-user", "batman");
        headers.add("x-rp-grp", "z-gateleen-user,z-gateleen-authenticated");  // no admin role!

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
        });

        Mockito.verifyZeroInteractions(response);
    }

    @Test
    public void userIsAuthorizedToReadItsOwnResourcesPatternGetsUpdatedOnEveryRequest(TestContext context) {
        String requestUri = "/resources/userspecific/batman/info";

        headers.add("x-user", "batman");
        headers.add("x-rp-grp", "z-gateleen-user,z-gateleen-authenticated");  // no admin role!

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());

        /*
         * First request is with the correct user
         */
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result()); // user batman should be authorized

            /*
             * test again with another user and check whether the pattern is updated for every request
             */
            CaseInsensitiveHeaders headers2 = new CaseInsensitiveHeaders();
            headers2.add("x-user", "robin");
            headers2.add("x-rp-grp", "z-gateleen-user,z-gateleen-authenticated");  // no admin role!
            AuthorizerRequest req2 = new AuthorizerRequest(HttpMethod.GET, requestUri, headers2, response);
            authorizer.authorize(req2).setHandler(event2 -> {
                context.assertTrue(event2.succeeded());
                context.assertFalse(event2.result()); // user robin should not be authorized
            });
        });

        assertForbidden(response);
    }

    @Test
    public void userIsAuthorizedToReadItsOwnResourcesAdditionalRegexGroups(TestContext context) {
        String requestUri = "/resources/userspecific/batman/items/123456/foo";

        headers.add("x-user", "batman");
        headers.add("x-rp-grp", "z-gateleen-user,z-gateleen-authenticated");  // no admin role!

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
        });

        Mockito.verifyZeroInteractions(response);
    }

    @Test
    public void userIsNotAuthorizedToReadResourcesOfOthers(TestContext context) {
        String requestUri = "/resources/userspecific/batman/info";

        headers.add("x-user", "robin");
        headers.add("x-rp-grp", "z-gateleen-user,z-gateleen-authenticated"); // no admin role!

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.GET, requestUri, headers, response);

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertFalse(event.result()); // false => not authorized
        });

        assertForbidden(response);
    }

    private void assertForbidden(DummyHttpServerResponse response){
        Mockito.verify(response, timeout(1000).times(1)).setStatusCode(eq(StatusCode.FORBIDDEN.getStatusCode()));
        Mockito.verify(response, timeout(1000).times(1)).setStatusMessage(eq(StatusCode.FORBIDDEN.getStatusMessage()));
        Mockito.verify(response, timeout(1000).times(1)).end(eq(StatusCode.FORBIDDEN.getStatusMessage()));
    }

    private void setupAcls() {
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

    static class AuthorizerRequest extends DummyHttpServerRequest {
        private final String uri;
        private final HttpMethod method;
        private final String body;
        private final CaseInsensitiveHeaders headers;
        private final HttpServerResponse response;

        AuthorizerRequest(HttpMethod method, String uri, CaseInsensitiveHeaders headers,
                          HttpServerResponse response) {
            this(method, uri, headers, "", response);
        }

        AuthorizerRequest(HttpMethod method, String uri, CaseInsensitiveHeaders headers,
                          String body, HttpServerResponse response) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.body = body;
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
        public MultiMap headers() {
            return headers;
        }

        @Override
        public HttpServerResponse response() {
            return response;
        }

        @Override
        public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
            bodyHandler.handle(Buffer.buffer(body));
            return this;
        }
    }
}