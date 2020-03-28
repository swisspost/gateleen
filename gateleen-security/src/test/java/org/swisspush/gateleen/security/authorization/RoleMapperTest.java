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
        context.assertTrue(roles.contains("domain1"));
    }

    @Test
    public void checkMappingWithKeep(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain2-user");
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size() == 2);
        context.assertTrue(roles.contains("domain2"));
        context.assertTrue(roles.contains("domain2-user"));
    }


    @Test
    public void checkStageMappingWithoutFurtherHit(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain-user-stage-p"); // will match the stage mapping but after that no other one
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size() == 0);
    }


    @Test
    public void checkStageMappingWithoutKeepOriginal(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain1-user-stage-p");
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size() == 1);
        context.assertTrue(roles.contains("domain1"));
    }

    @Test
    public void checkStageMappingWithKeepOriginal(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain2-user-stage-p");
        roles = roleMapper.mapRoles(roles);
        context.assertNotNull(roles);
        context.assertTrue(roles.size() == 2);
        context.assertTrue(roles.contains("domain2-user"));
        context.assertTrue(roles.contains("domain2"));
    }




/*
    // change the test resources correspondingly between the tests manually
    @Test
    public void checkLocalMappingPerformance(TestContext context) {
        Set<String> roles = new HashSet<>();
        roles.add("domain1-user-stage-p");
        Set<String> resultingRoles;
        long startTime = System.currentTimeMillis();
        System.out.println("Start: " + startTime);
        for (int i = 0;i<10000;i++) {
            resultingRoles = roleMapper.mapRoles(roles);
            context.assertNotNull(resultingRoles);
            context.assertTrue(resultingRoles.size() == 1);
            context.assertTrue(resultingRoles.contains("domain1"));
        }
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        System.out.println("End: " + startTime);
        System.out.println("Duration: " + duration);
    }
*/



    private void setupRoleMapper() {
        storage.putMockData(ROLEMAPPER, ResourcesUtils.loadResource(ROLEMAPPER_DIR + "rolemapper", true));
    }

}