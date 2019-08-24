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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.timeout;

/**
 * Tests for the {@link RoleMapper} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RoleMapperTest {

    private RoleMapper roleMapper;
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
        setupRoleMapper();
        roleMapper = new RoleMapper(storage,"/gateleen/server/security/v1/");

    }

    @Test
    public void checkNoMapping(TestContext context)
    {
        Set<String> roles = new HashSet<>();
        roles.add("domain-user");
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size()==1);
        context.assertTrue(roles.contains("domain-user"));
    }

    @Test
    public void checkMappingWithoutKeep(TestContext context)
    {
        Set<String> roles = new HashSet<>();
        roles.add("domain1-user");
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size()==1);
        context.assertTrue(roles.contains("domain"));
    }

    @Test
    public void checkMappingWithKeep(TestContext context)
    {
        Set<String> roles = new HashSet<>();
        roles.add("domain2-user");
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size()==2);
        context.assertTrue(roles.contains("domain"));
        context.assertTrue(roles.contains("domain2-user"));
    }



    private void setupRoleMapper(){
        storage.putMockData(ROLEMAPPER, ResourcesUtils.loadResource(ROLEMAPPER_DIR + "rolemapper", true));
    }

 }