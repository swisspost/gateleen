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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


/**
 * Tests for the {@link RoleMapper} class
 */
@RunWith(VertxUnitRunner.class)
public class RoleMapperTest {

    private RoleMapper roleMapper;
    private Vertx vertx;
    private MockResourceStorage storage;

    private static final String ROLEMAPPER = "/gateleen/server/security/v1/rolemapper";
    private static final String ROLEMAPPER_DIR = "rolemapper/";


    @Rule
    public Timeout rule = Timeout.seconds(5);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        storage = new MockResourceStorage();
        setupRoleMapper();
        Map<String, Object> properties = new HashMap<>();
        properties.put("STAGE", "int");
        roleMapper = new RoleMapper(storage, "/gateleen/server/security/v1/", properties);

    }

    @Test
    public void checkNoMapping(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain-user");
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        context.assertNotNull(mappedRoles);
        context.assertTrue(mappedRoles.size() == 1);
        context.assertTrue(mappedRoles.containsKey("domain-user"));
    }

    @Test
    public void checkMappingWithoutKeep(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain1-user");
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        context.assertNotNull(mappedRoles);
        context.assertTrue(mappedRoles.size() == 2);
        context.assertTrue(mappedRoles.get("domain1").forward == true);
        context.assertTrue(mappedRoles.get("domain1-user").forward == false);
    }

    @Test
    public void checkMappingWithKeep(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain2-user");
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        context.assertNotNull(mappedRoles);
        context.assertTrue(mappedRoles.size() == 2);
        context.assertTrue(mappedRoles.containsKey("domain2"));
        context.assertTrue(mappedRoles.containsKey("domain2-user"));
    }


    @Test
    public void checkStageMappingWithoutFurtherHit(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain-user-stage-prod"); // will match the stage mapping but after that no other one
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        context.assertNotNull(mappedRoles);
        context.assertTrue(mappedRoles.size() == 2);
        context.assertTrue(mappedRoles.get("domain-user-stage-prod").forward == false);
        context.assertTrue(mappedRoles.get("domain-user").forward == true);
    }

    @Test
    public void checkStageMappingWithFurtherHit(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain-admin-stage-prod"); // will match the stage mapping but after that no other one
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        context.assertNotNull(mappedRoles);
        context.assertTrue(mappedRoles.size() == 3);
        context.assertTrue(mappedRoles.get("domain-admin-stage-prod").forward == false);
        context.assertTrue(mappedRoles.get("domain-admin").forward == true);
        context.assertTrue(mappedRoles.get("domain").forward == false);
    }

    @Test
    public void checkStageMappingWithoutAnyKeepOriginal(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain1-user-stage-prod");
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        context.assertNotNull(mappedRoles);
        context.assertTrue(mappedRoles.size() == 3);
        context.assertTrue(mappedRoles.get("domain1-user-stage-prod").forward == false);
        context.assertTrue(mappedRoles.get("domain1-user").forward == false);
        context.assertTrue(mappedRoles.get("domain1").forward == true);
    }

    @Test
    public void checkStageMappingWithKeepLastOriginal(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain2-user-stage-prod");
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        context.assertNotNull(mappedRoles);
        context.assertTrue(mappedRoles.size() == 3);
        context.assertTrue(mappedRoles.get("domain2-user-stage-prod").forward == false);
        context.assertTrue(mappedRoles.get("domain2-user").forward == true);
        context.assertTrue(mappedRoles.get("domain2").forward == true);
    }


    @Test
    public void checkStageMappingWithKeepFirstOriginalAndEnvironementProperty(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain1-user-stage-int");
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        context.assertNotNull(mappedRoles);
        context.assertTrue(mappedRoles.size() == 3);
        context.assertTrue(mappedRoles.get("domain1-user-stage-int").forward == true);
        context.assertTrue(mappedRoles.get("domain1-user-int").forward == false);
        context.assertTrue(mappedRoles.get("domain1").forward == true);
    }

    @Test
    public void checkStageMappingWithKeepAllOriginalAndEnvironementProperty(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain2-user-stage-testint");
        Map<String, RoleMapper.MappedRole> mappedRoles = roleMapper.mapRoles(roles);
        context.assertNotNull(mappedRoles);
        context.assertTrue(mappedRoles.size() == 3);
        context.assertTrue(mappedRoles.containsKey("domain2-user-stage-testint"));
        context.assertTrue(mappedRoles.containsKey("domain2-user-int"));
        context.assertTrue(mappedRoles.containsKey("domain2"));
    }


    // Test method to make manual performance tests with the ruleset eg. measure runtime
    // with different mapping rule sets.
    @Test
    public void checkLocalMappingPerformance(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain1-user-stage-prod");
        Map<String, RoleMapper.MappedRole> mappedRoles;
        long startTime = System.currentTimeMillis();
        System.out.println("Start: " + startTime);
        for (int i = 0; i < 10000; i++) {
            mappedRoles = roleMapper.mapRoles(roles);
            if (i == 0) {  // only necessary to be executed once
                context.assertNotNull(mappedRoles);
                context.assertTrue(mappedRoles.size() == 3);
                context.assertTrue(mappedRoles.get("domain1-user-stage-prod").forward == false);
                context.assertTrue(mappedRoles.get("domain1-user").forward == false);
                context.assertTrue(mappedRoles.get("domain1").forward == true);
            }
        }
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        System.out.println("End: " + startTime);
        System.out.println("Duration: " + duration);
    }


    private void setupRoleMapper() {
        storage.putMockData(ROLEMAPPER, ResourcesUtils.loadResource(ROLEMAPPER_DIR + "rolemapper", true));
    }

}