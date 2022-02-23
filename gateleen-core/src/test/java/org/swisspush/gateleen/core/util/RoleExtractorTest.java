package org.swisspush.gateleen.core.util;

import io.vertx.core.MultiMap;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;

import java.util.Set;

/**
 * Tests for the {@link RoleExtractor} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RoleExtractorTest {

    private static final String ROLE_PATTERN = "^z-gateleen[-_](.*)$";
    private static final String groupHeader = "x-rp-grp";
    private static final String roleHeader = "x-roles";

    private RoleExtractor roleExtractor;

    @Before
    public void setUp(){
        roleExtractor = new RoleExtractor(ROLE_PATTERN);
    }

    @Test
    public void testExtractRolesNotMatchingPattern(TestContext context) throws Exception {
        context.assertNull(roleExtractor.extractRoles(new TestHttpServerRequest(
                MultiMap.caseInsensitiveMultiMap())), "No roles should have been found and returned as null");

        context.assertTrue(roleExtractor.extractRoles(new TestHttpServerRequest(
                buildGroupHeaders("grp_1,grp_2,grp_3"))).isEmpty(), "An empty set of roles should have been returned");
        context.assertTrue(roleExtractor.extractRoles(new TestHttpServerRequest(
                buildRoleHeaders("grp_1,grp_2,grp_3"))).isEmpty(), "An empty set of roles should have been returned");

        context.assertTrue(roleExtractor.extractRoles(new TestHttpServerRequest(
                buildGroupHeaders(""))).isEmpty(), "An empty set of roles should have been returned");
        context.assertTrue(roleExtractor.extractRoles(new TestHttpServerRequest(
                buildRoleHeaders(""))).isEmpty(), "An empty set of roles should have been returned");
    }

    @Test
    public void testExtractRolesMatchingPattern(TestContext context) throws Exception {
        context.assertFalse(roleExtractor.extractRoles(new TestHttpServerRequest(
                buildGroupHeaders("grp_1,grp_2,grp_3,z-gateleen-grp_4"))).isEmpty(), "A non-empty set of roles should have been returned");
        context.assertFalse(roleExtractor.extractRoles(new TestHttpServerRequest(
                buildRoleHeaders("grp_1,grp_2,grp_3,z-gateleen-grp_4"))).isEmpty(), "A non-empty set of roles should have been returned");

        Set<String> groupRoles = roleExtractor.extractRoles(new TestHttpServerRequest(
                buildGroupHeaders("z-gateleen-abc,z-gateleen-xyz,grp_3,z-gateleen-grp_4,gateleen-grp_5")));

        context.assertEquals(3, groupRoles.size());
        context.assertTrue(groupRoles.contains("abc"));
        context.assertTrue(groupRoles.contains("xyz"));
        context.assertTrue(groupRoles.contains("grp_4"));

        context.assertFalse(groupRoles.contains("grp_3"));
        context.assertFalse(groupRoles.contains("gateleen-grp_5"));

        Set<String> roleRoles = roleExtractor.extractRoles(new TestHttpServerRequest(
                buildRoleHeaders("z-gateleen-abc,z-gateleen-xyz,grp_3,z-gateleen-grp_4,gateleen-grp_5")));

        context.assertEquals(3, roleRoles.size());
        context.assertTrue(roleRoles.contains("abc"));
        context.assertTrue(roleRoles.contains("xyz"));
        context.assertTrue(roleRoles.contains("grp_4"));

        context.assertFalse(roleRoles.contains("grp_3"));
        context.assertFalse(roleRoles.contains("gateleen-grp_5"));
    }

    @Test
    public void testExtractRolesCaseInsitivity(TestContext context) throws Exception {

        Set<String> groupRoles = roleExtractor.extractRoles(new TestHttpServerRequest(
                buildGroupHeaders("z-gateleen-abC,z-gateleen-XYZ,z-gateleen-DeF,z-gateleen-ghi")));

        context.assertEquals(4, groupRoles.size());
        context.assertTrue(groupRoles.contains("abc"));
        context.assertTrue(groupRoles.contains("xyz"));
        context.assertTrue(groupRoles.contains("def"));
        context.assertTrue(groupRoles.contains("ghi"));

        Set<String> roleRoles = roleExtractor.extractRoles(new TestHttpServerRequest(
                buildRoleHeaders("z-gateleen-abC,z-gateleen-XYZ,z-gateleen-DeF,z-gateleen-ghi")));

        context.assertEquals(4, roleRoles.size());
        context.assertTrue(roleRoles.contains("abc"));
        context.assertTrue(roleRoles.contains("xyz"));
        context.assertTrue(roleRoles.contains("def"));
        context.assertTrue(roleRoles.contains("ghi"));
    }

    private MultiMap buildGroupHeaders(String headerValues){
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(groupHeader, headerValues);
        return headers;
    }

    private MultiMap buildRoleHeaders(String headerValues){
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(roleHeader, headerValues);
        return headers;
    }

    class TestHttpServerRequest extends DummyHttpServerRequest {
        private MultiMap headers;

        public TestHttpServerRequest(MultiMap headers) {
            this.headers = headers;
        }

        @Override public MultiMap headers() { return headers; }
    }
}
