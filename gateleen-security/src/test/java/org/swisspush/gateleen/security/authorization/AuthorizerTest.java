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
import io.vertx.ext.unit.Async;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.timeout;

/**
 * Tests for the {@link AclFactory} class
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
    public void testPattern(){
//        Pattern pattern = Pattern.compile("/gateleen/(.+/)*info(\\\\?.*)?");
        Pattern pattern = Pattern.compile("/nemo/[^/]+/info");
//        String uri = "/gateleen/zzz/v1/scans/zips/601060/types/470/test/modes/TestMode/messages/_hooks/listeners/http/push/dx1489066323470-zzz_/gateleen/zzz/v1/scans/zips/601060/types/470/test/modes/TestMode/messages/";
//        String uri = "/gateleen/zzz/v1/scans/zips/601060/types/470/test/modes/TestMode/messages/_hooks/listeners/http/push/dx1489066323470-zzz_/gateleen/zzz/v1/scans/zips/601060/adfasdgasdgas/dgs/adg/asldg/sdgsadg/asdgasdgasdgasdg";
        String uri = "/nemo/abc/dse/info";

        long start = System.currentTimeMillis();
        Matcher matcher = pattern.matcher(uri);
        long end;
        if(matcher.matches()){
            end = System.currentTimeMillis();
            System.out.println("Matched: " + (end - start) + " ms");
        } else {
            end = System.currentTimeMillis();
            System.out.println("No Match: " + (end - start) + " ms");
        }

    }

    @Test
    public void testPerformanceOfAuthorize(TestContext context) {
        Async async = context.async();
        String requestUri = "/gateleen/zzz/v1/scans/zips/601060/types/470/test/modes/TestMode/messages/_hooks/listeners/http/push/dx1489066323470-zzz_/gateleen/zzz/v1/scans/zips/601060/types/470/test/modes/TestMode/messages/";

        CaseInsensitiveHeaders headers = new CaseInsensitiveHeaders();
//        headers.add("x-rp-grp", "z-gateleen-admin");

        headers.add("x-rp-grp", "z-gateleen-admin,z-gateleen-authenticated,z-gateleen-developer,z-gateleen-device," +
                "z-gateleen-everyone,z-gateleen-sales,z-gateleen-factory,z-gateleen-guest,z-gateleen-office," +
                "z-gateleen-transport,z-gateleen-accounting,z-gateleen-management,z-gateleen-production,z-gateleen-partner," +
                "z-gateleen-support,z-gateleen-medical,z-gateleen-resources,z-gateleen-external,z-gateleen-federal," +
                "z-gateleen-internal,z-gateleen-sorting,z-gateleen-delivery,z-gateleen-agency,z-gateleen-security," +
                "z-gateleen-technical_support,z-gateleen-offshore,z-gateleen-user");

//        headers.add("x-rp-grp", "z-gateleen-admin,z-gateleen-authenticated,z-gateleen-developer,z-gateleen-device," +
//                "z-gateleen-everyone,z-gateleen-sales,z-gateleen-factory,z-gateleen-guest,z-gateleen-office," +
//                "z-gateleen-transport,z-gateleen-accounting,z-gateleen-management,z-gateleen-production,z-gateleen-partner," +
//                "z-gateleen-support,z-gateleen-medical,z-gateleen-resources,z-gateleen-external,z-gateleen-federal," +
//                "z-gateleen-internal,z-gateleen-sorting,z-gateleen-delivery,z-gateleen-agency,z-gateleen-security," +
//                "z-gateleen-technical_support,z-gateleen-offshore");

        DummyHttpServerResponse response = Mockito.spy(new DummyHttpServerResponse());
        AuthorizerRequest req = new AuthorizerRequest(HttpMethod.DELETE, requestUri, headers, response);

//        authorizer.authorize(req, event -> {
//            context.assertTrue(true);
//            async.complete();
//        });

        authorizer.authorize(req).setHandler(event -> {
            context.assertTrue(event.succeeded());
            context.assertTrue(event.result());
            async.complete();
        });

//        Mockito.verify(response, Mockito.timeout(1000).times(1)).setStatusCode(eq(StatusCode.OK.getStatusCode()));
    }

    private void setupAcls(){
        JsonObject acls = new JsonObject();
        acls.put("acls", new JsonArray(Arrays.asList("admin", "authenticated", "developer", "device", "everyone", "sales", "factory", "guest", "office",
                "transport", "accounting", "management", "production", "partner", "support", "medical", "resources", "external",
                "federal", "internal", "sorting", "delivery", "agency", "security", "technical_support", "offshore", "user")));

        storage.putMockData(ACLS, acls.encode());

        storage.putMockData(ACLS + "admin", ResourcesUtils.loadResource(ACLS_DIR + "admin", true));
        storage.putMockData(ACLS + "authenticated", ResourcesUtils.loadResource(ACLS_DIR + "authenticated", true));
        storage.putMockData(ACLS + "developer", ResourcesUtils.loadResource(ACLS_DIR + "developer", true));
        storage.putMockData(ACLS + "device", ResourcesUtils.loadResource(ACLS_DIR + "device", true));
        storage.putMockData(ACLS + "everyone", ResourcesUtils.loadResource(ACLS_DIR + "everyone", true));
        storage.putMockData(ACLS + "sales", ResourcesUtils.loadResource(ACLS_DIR + "sales", true));
        storage.putMockData(ACLS + "factory", ResourcesUtils.loadResource(ACLS_DIR + "factory", true));
        storage.putMockData(ACLS + "guest", ResourcesUtils.loadResource(ACLS_DIR + "guest", true));
        storage.putMockData(ACLS + "office", ResourcesUtils.loadResource(ACLS_DIR + "office", true));
        storage.putMockData(ACLS + "transport", ResourcesUtils.loadResource(ACLS_DIR + "transport", true));
        storage.putMockData(ACLS + "accounting", ResourcesUtils.loadResource(ACLS_DIR + "accounting", true));
        storage.putMockData(ACLS + "management", ResourcesUtils.loadResource(ACLS_DIR + "management", true));
        storage.putMockData(ACLS + "production", ResourcesUtils.loadResource(ACLS_DIR + "production", true));
        storage.putMockData(ACLS + "partner", ResourcesUtils.loadResource(ACLS_DIR + "partner", true));
        storage.putMockData(ACLS + "support", ResourcesUtils.loadResource(ACLS_DIR + "support", true));
        storage.putMockData(ACLS + "medical", ResourcesUtils.loadResource(ACLS_DIR + "medical", true));
        storage.putMockData(ACLS + "resources", ResourcesUtils.loadResource(ACLS_DIR + "resources", true));
        storage.putMockData(ACLS + "external", ResourcesUtils.loadResource(ACLS_DIR + "external", true));
        storage.putMockData(ACLS + "federal", ResourcesUtils.loadResource(ACLS_DIR + "federal", true));
        storage.putMockData(ACLS + "internal", ResourcesUtils.loadResource(ACLS_DIR + "internal", true));
        storage.putMockData(ACLS + "sorting", ResourcesUtils.loadResource(ACLS_DIR + "sorting", true));
        storage.putMockData(ACLS + "delivery", ResourcesUtils.loadResource(ACLS_DIR + "delivery", true));
        storage.putMockData(ACLS + "agency", ResourcesUtils.loadResource(ACLS_DIR + "agency", true));
        storage.putMockData(ACLS + "security", ResourcesUtils.loadResource(ACLS_DIR + "security", true));
        storage.putMockData(ACLS + "technical_support", ResourcesUtils.loadResource(ACLS_DIR + "technical_support", true));
        storage.putMockData(ACLS + "offshore", ResourcesUtils.loadResource(ACLS_DIR + "offshore", true));
        storage.putMockData(ACLS + "user", ResourcesUtils.loadResource(ACLS_DIR + "user", true));
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