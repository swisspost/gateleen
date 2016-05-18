package org.swisspush.gateleen.user;

import org.swisspush.gateleen.AbstractTest;
import org.swisspush.gateleen.TestUtils;
import com.jayway.awaitility.Duration;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.response.Response;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.restassured.RestAssured.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;

@RunWith(VertxUnitRunner.class)
public class RoleProfileTest extends AbstractTest {

    String allowedProfilePropertiesOriginal;

    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath("/server/roles/v1");
        RestAssured.requestSpecification.baseUri("http://localhost:" + MAIN_PORT + ROOT);
    }

    @Test
    public void testUpdateStringProperty(TestContext context) {
        Async async = context.async();

        String value = "bar_" + System.currentTimeMillis();

        // Add a profile variable
        given().body("{ \"foo\": \"" + value + "\" }").when().put("known-role/profile").then().assertThat().statusCode(200);

        // Check it
        get("known-role/profile").then().assertThat().body("foo", equalTo(value));

        async.complete();
    }

    @Test
    public void testUpdateObjectProperty(TestContext context) {
        Async async = context.async();

        // Add a profile variable
        given().body("{ \"foo\": { \"bla\": \"bar\" } }").when().put("known-role/profile").then().assertThat().statusCode(400);

        // Check it
        get("known-role/profile").then().assertThat().statusCode(404);

        async.complete();
    }

    private String setRoleProfileProperty() {
        RestAssured.requestSpecification.basePath("server/roles/v1");

        String value = "bar_" + System.currentTimeMillis();

        // Add a role profile variable
        given().body("{ \"foo\": \"" + value + "\" }").when().put("known-role/profile").then().assertThat().statusCode(200);

        return value;
    }

    private String deleteRoleProfile() {
        RestAssured.requestSpecification.basePath("server/roles/v1");

        String value = "bar_" + System.currentTimeMillis();

        // Add a role profile variable
        given().body("{ \"foo\": \"" + value + "\" }").when().put("known-role/profile").then().assertThat().statusCode(200);

        return value;
    }

    @Test
    public void testUpdateRoleProfileGetUserWithTheSameRole(TestContext context) throws InterruptedException {
        Async async = context.async();

        // Arrange
        String roleProfilePropertyValue = setRoleProfileProperty();

        // Act / Assert
        // we have to wait here, cause the role profile needs to be published over the vert.x bus
        RestAssured.requestSpecification.basePath("/server/users/v1");

        await().atMost(new Duration(8, TimeUnit.SECONDS)).until(() -> given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").when().get("known-user/profile").then().extract().body().jsonPath().getString("foo"), equalTo(roleProfilePropertyValue));

        async.complete();
    }

    @Test
    public void testUpdateRoleProfileGetUserWithAnotherRole(TestContext context) throws InterruptedException {
        Async async = context.async();

        // Arrange
        setRoleProfileProperty();

        // Act / Assert
        // we have to wait here, cause the role profile needs to be published over the vert.x bus
        RestAssured.requestSpecification.basePath("/server/users/v1");

        await().atMost(new Duration(5, TimeUnit.SECONDS)).until(() -> given().header("x-rp-grp", "z-gateleen-admin").when().get("known-user/profile").then().extract().body().jsonPath().getString("foo"), nullValue());

        async.complete();
    }

    @Test
    /**
     * We test the following scenario:
     * <li>
     * <ul>
     * Create a role profile.
     * </ul>
     * <ul>
     * Get the user profile with the role of the role profile, means the userprofile will contain the attribute of the roleprofile.
     * </ul>
     * <ul>
     * Put the userprofile. At this point the userprofile should be stored without the additional roleprofile attribute.
     * </ul>
     * <ul>
     * Delete the roleprofile.
     * </ul>
     * <ul>
     * Get the userprofile again with the role of the roleprofile we created. Now we should get the userprofile without the additional roleprofile attribute.
     * </ul>
     * </li>
     */
    public void testAddRoleProfileGetUserProfileAndPutItThenDeleteRoleProfileAndGetTheUserProfileAgain(TestContext context) throws InterruptedException {
        Async async = context.async();

        jedis.flushAll();

        // Arrange
        String roleProfilePropertyValue = setRoleProfileProperty();

        TestUtils.waitSomeTime(5);
        RestAssured.requestSpecification.basePath("/server/users/v1");
        Response response = given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").get("known-user/profile").then().assertThat().body("foo", equalTo(roleProfilePropertyValue)).extract().response();

        String responseAsString = response.asString();
        System.out.println("got profile for known-user: " + responseAsString);

        given().body(responseAsString).when().put("known-user/profile").then().assertThat().statusCode(200);

        System.out.println("delete known-role profile");
        RestAssured.requestSpecification.basePath("/server/roles/v1");
        delete("known-role/profile");

        // Act / Assert
        // we have to wait here, cause the role profile needs to be published over the vert.x bus
        System.out.println("get the known-user profile, after deletion of known-role profile");
        RestAssured.requestSpecification.basePath("/server/users/v1");
        await().atMost(new Duration(5, TimeUnit.SECONDS)).until(() -> given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").when().get("known-user/profile").then().extract().body().jsonPath().getString("foo"), nullValue());

        async.complete();
    }

}
