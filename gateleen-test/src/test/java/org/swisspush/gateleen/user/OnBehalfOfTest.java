package org.swisspush.gateleen.user;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import com.google.common.collect.ImmutableMap;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static io.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.*;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class OnBehalfOfTest extends AbstractTest {

    private final String USERS_BASEPATH = "/server/users/v1";
    private final String ROUTING_BASEPATH = "/server/admin/v1/routing";
    private final String ONBEHALFOF_BASEPATH = "/testing/restassured";

    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath(USERS_BASEPATH);
        RestAssured.requestSpecification.baseUri("http://localhost:" + MAIN_PORT + ROOT);
    }

    @Test
    public void testOnBehalfOfRequests(TestContext context) throws InterruptedException {
        Async async = context.async();
        delete();

        /* PUT custom allowedProfileProperties resource with allowed tour and zip properties */
        given().body("{\"properties\": [\"tour\", \"zip\"]}").when().put("allowedProfileProperties").then().assertThat().statusCode(200);

        // create user A and user B profiles
        RestAssured.requestSpecification.basePath(USERS_BASEPATH);
        given().body(createUserA().toString()).when().put("user_A/profile").then().assertThat().statusCode(200);
        get("user_A/profile").then().assertThat().body("personalNumber", equalTo("11111111")).body("username", equalTo("user_A")).body("zip", equalTo("111")).body("tour", equalTo("123456")).body("department", equalTo("user_A_department"));

        given().body(createUserB().toString()).when().put("user_B/profile").then().assertThat().statusCode(200);
        get("user_B/profile").then().assertThat().body("personalNumber", equalTo("22222222")).body("username", equalTo("user_B")).body("zip", equalTo("222")).body("tour", equalTo("654321")).body("department", equalTo("user_B_department"));

        String TEST_RULE_NAME = ROOT + "/testing/restassured/onbehalfof";

        // load routing rules
        RestAssured.requestSpecification.basePath(ROUTING_BASEPATH);

        // create new test routing rule
        JsonObject rules = TestUtils.addRoutingRuleMainStorage(new JsonObject());
        JsonObject newRule = createOnBehalfOfRoutingRule();
        JsonObject extendedRules = TestUtils.addRoutingRule(rules, TEST_RULE_NAME, newRule);

        // put testing roule to gateleen
        TestUtils.putRoutingRules(extendedRules);

        // test if new rule is in rules resource
        String body = get("rules").then().extract().asString();
        System.out.println(" Body : " + body);
        get("rules").then().assertThat().statusCode(200).body(containsString(TEST_RULE_NAME));

        // testing
        RestAssured.requestSpecification.basePath(ONBEHALFOF_BASEPATH);

        // request from user A without on-behalf-of header
        given().header("x-rp-usr", "user_A").when().get("onbehalfof").then().assertThat().statusCode(200).body(not(containsString("x-on-behalf-of"))).body(containsString("x-rp-usr: user_A")).body(containsString("x-user-department: user_A_department")).body(containsString("x-user-personalNumber: 11111111")).body(containsString("x-user-tour: 123456")).body(containsString("x-user-username: user_A"))
                .body(containsString("x-user-zip: 111"));

        // request from user A on-behalf-of user B
        given().header("x-rp-usr", "user_A").header("x-on-behalf-of", "user_B").when().get("onbehalfof").then().assertThat().statusCode(200).body(containsString("x-on-behalf-of: user_B")).body(containsString("x-rp-usr: user_A")).body(containsString("x-user-department: user_B_department")).body(containsString("x-user-personalNumber: 22222222")).body(containsString("x-user-tour: 654321"))
                .body(containsString("x-user-username: user_B")).body(containsString("x-user-zip: 222"));

        async.complete();
    }

    private JsonObject createUserA() {
        JsonObject userA = new JsonObject();
        userA.put("personalNumber", "11111111");
        userA.put("username", "user_A");
        userA.put("zip", "111");
        userA.put("tour", "123456");
        userA.put("department", "user_A_department");
        return userA;
    }

    private JsonObject createUserB() {
        JsonObject userB = new JsonObject();
        userB.put("personalNumber", "22222222");
        userB.put("username", "user_B");
        userB.put("zip", "222");
        userB.put("tour", "654321");
        userB.put("department", "user_B_department");
        return userB;
    }

    private JsonObject createOnBehalfOfRoutingRule() {
        JsonObject newRule = TestUtils.createRoutingRule(ImmutableMap.of(
                "description",
                "RoutingRule to test the x-on-behalf-header.",
                "url",
                "http://localhost:" + MAIN_PORT + ROOT + "/debug"));

        JsonArray profileArray = new JsonArray();
        profileArray.add("personalNumber");
        profileArray.add("zip");
        profileArray.add("tour");
        profileArray.add("username");
        profileArray.add("department");
        newRule.put("profile", profileArray);
        return newRule;
    }
}
