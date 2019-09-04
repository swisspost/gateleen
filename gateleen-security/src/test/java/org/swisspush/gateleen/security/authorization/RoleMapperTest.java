package org.swisspush.gateleen.security.authorization;


import io.vertx.core.Vertx;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.storage.MockResourceStorage;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import java.util.HashSet;
import java.util.Set;


/**
 * Tests for the {@link RoleMapper} class
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
    public void setUp() {
        vertx = Vertx.vertx();
        storage = new MockResourceStorage();
        setupRoleMapper();
        roleMapper = new RoleMapper(storage, "/gateleen/server/security/v1/");

    }

    @Test
    public void checkNoMapping(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain-user");
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size() == 1);
        context.assertTrue(roles.contains("domain-user"));
    }

    @Test
    public void checkMappingWithoutKeep(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain1-user");
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size() == 1);
        context.assertTrue(roles.contains("domain"));
    }

    @Test
    public void checkMappingWithKeep(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain2-user");
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size() == 2);
        context.assertTrue(roles.contains("domain"));
        context.assertTrue(roles.contains("domain2-user"));
    }


    private void setupRoleMapper() {
        storage.putMockData(ROLEMAPPER, ResourcesUtils.loadResource(ROLEMAPPER_DIR + "rolemapper", true));
    }

}