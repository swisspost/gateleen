package org.swisspush.gateleen.user;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.AbstractTest;

import static io.restassured.RestAssured.*;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;

@RunWith(VertxUnitRunner.class)
public class UserProfileTest extends AbstractTest {

    public void initRestAssured() {
        super.initRestAssured();
        RestAssured.requestSpecification.basePath("/server/users/v1");
        RestAssured.requestSpecification.baseUri("http://localhost:" + MAIN_PORT + ROOT);
    }

    @Test
    public void testGetNew(TestContext context) {
        Async async = context.async();
        when().get("unknown-user/profile").then().assertThat().body("lang", equalTo("de")).body("mail", equalTo("unknown")).body("department", equalTo("unknown")).body("personalNumber", equalTo("unknown"));
        async.complete();
    }

    @Test
    public void testUpdateWithoutBody(TestContext context) {
        Async async = context.async();
        // Add a profile variable without body
        when().put("known-user/profile").then().assertThat().statusCode(400);
        async.complete();
    }

    @Test
    /**
     * This test verifies the relaxation of the profile update rules.
     * Now a user A should be able to update user B's profile.
     */
    public void testUpdateProfileOnBehalfOf(TestContext context) {
        Async async = context.async();

        // A user with the admin role is permitted to update another users profile
        given().header("x-rp-grp", "z-gateleen-user").header("x-rp-usr", "user_A").body("{ \"spn\": \"myVal\" }").when().put("user_A/profile").then().assertThat().statusCode(200);

        // Check it
        get("user_A/profile").then().assertThat().body("spn", equalTo("myVal"));

        // A user without the admin role but the on-behalf-of header is permitted to update another users profile
        given().header("x-rp-grp", "z-gateleen-user").header("x-rp-usr", "user_B").header("x-on-behalf-of", "user_A").body("{ \"spn\": \"myValModified\" }").when().put("user_A/profile").then().assertThat().statusCode(200);

        // Check it
        get("user_A/profile").then().assertThat().body("spn", equalTo("myValModified"));

        async.complete();
    }

    @Test
    /**
     * We test the following scenario:
     * <li>
     * <ul>
     * Add all possible profile attributes
     * </ul>
     * <ul>
     * Add one profile attribute that is not allowed
     * </ul>
     * <ul>
     * Put the profile
     * </ul>
     * <ul>
     * Get the profile and check if the allowed attributes were stored and the not allowed attribute was stripped
     * </ul>
     * </li>
     */
    public void testAddAllAllowedAttributesAndOneNotAllowedAttribute(TestContext context) throws InterruptedException {
        Async async = context.async();

        // Arrange
        RestAssured.requestSpecification.basePath("/server/users/v1");
        Response response = given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").get("known-user/profile").then().assertThat().body("personalNumber", equalTo("unknown")).extract().response();

        String responseAsString = response.asString();

        // Only personal numbers '\d*{8}' are accepted
        final String testPersonalNumber = "45482365";

        System.out.println("got profile for known-user: " + responseAsString);
        JsonObject profile = new JsonObject(responseAsString);
        profile.put("username", "username");
        profile.put("personalNumber", testPersonalNumber);
        profile.put("mail", "mail");
        profile.put("department", "department");
        profile.put("lang", "lang");
        profile.put("tour", "tour");
        profile.put("zip", "zip");
        profile.put("context", "context");
        profile.put("contextIsDefault", "contextIsDefault");
        profile.put("passkeyChanged", "passkeyChanged");
        profile.put("volumeBeep", "volumeBeep");
        profile.put("torchMode", "torchMode");
        profile.put("spn", "spn");
        profile.put("attrSetByClient", "blabla");

        given().body(profile.toString()).when().put("known-user/profile").then().assertThat().statusCode(200);

        // Act / Assert
        // we have to wait here, cause the role profile needs to be published over the vert.x bus
        System.out.println("get the known-user profile, after deletion of known-role profile");
        RestAssured.requestSpecification.basePath("/server/users/v1");
        await().atMost(FIVE_SECONDS).until(() -> given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").when().get("known-user/profile").then().extract().body().jsonPath().getString("attrSetByClient"), nullValue());

        given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").when().get("known-user/profile").then().assertThat().body("username", equalTo("username")).body("personalNumber", equalTo(testPersonalNumber)).body("mail", equalTo("mail")).body("department", equalTo("department")).body("lang", equalTo("lang")).body("tour", equalTo("tour")).body("zip", equalTo("zip"))
                .body("context", equalTo("context")).body("contextIsDefault", equalTo("contextIsDefault")).body("passkeyChanged", equalTo("passkeyChanged")).body("volumeBeep", equalTo("volumeBeep")).body("torchMode", equalTo("torchMode")).body("spn", equalTo("spn"));

        async.complete();
    }

    /**
     * Tests that a fresh profile (got using GET) already contains valid values from header.
     */
    @Test
    public void testValuesAreAlreadyInTheProfileOnInitialGetIfInHeader(TestContext context) throws InterruptedException {
        Async async = context.async();

        RestAssured.requestSpecification.basePath("/server/users/v1");

        // 'x-rp-employeeid' is already correct in the first GET call, so this value should already be in the profile.
        given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").
                header("x-rp-usr", "known-user").
                header("x-rp-employeeid", "12345678").
                get("known-user/profile").
                then().assertThat().body("personalNumber", equalTo("12345678")).extract().response();

        async.complete();
    }

    /**
     * <p>Issue: STARG-66</p>
     *
     * Makes sure that values are always updated from headers (even on GET) if the value in the
     * header is 'valid' and the value in the provide is invalid. ...and that valid values in the
     * profile are not updated if the header value is invalid.
     */
    @Test
    public void testWillUpdateAInitiallyInvalidXrpUsr_original(TestContext context) throws InterruptedException {
        Async async = context.async();

        RestAssured.requestSpecification.basePath("/server/users/v1");

        // First we have a 'unknown' personal number. Happens if the proxy does not provide the personal number
        // in the header (no 'x-rp-employeeid').
        given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").
                header("x-rp-usr", "known-user").
                get("known-user/profile").
                then().assertThat().body("personalNumber", equalTo("unknown")).extract().response();

        // WITHOUT PUTing, the proxy is now OK again (no bug) and provides a valid personal number....
        // and the profile should be updated (even if this is a GET and not a PUT).
        final String testValidPersonalNumber = "45482365";
        given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").
                header("x-rp-usr", "known-user").
                header("x-rp-employeeid", testValidPersonalNumber).
                get("known-user/profile").
                then().assertThat().body("personalNumber", equalTo(testValidPersonalNumber)).extract().response();

        // Now the proxy is is buggy again and does not provide the employeeid... since a "null" employeeid is invalid,
        // this GET should not update the profile (will not replace a valid value with a invalid one).
        given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").
                header("x-rp-usr", "known-user").
                get("known-user/profile").
                then().assertThat().body("personalNumber", equalTo(testValidPersonalNumber)).extract().response();

        // Still the correct employee ID?
        given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").
                header("x-rp-usr", "known-user").
                get("known-user/profile").
                then().assertThat().body("personalNumber", equalTo(testValidPersonalNumber)).extract().response();

        // Now the proxy is buggy again and provides a crappy employee-ID (one that does not conform to the defined
        // regex '\d*{8}'. This should not update the profile value.
        given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").
                header("x-rp-usr", "known-user").
                header("x-rp-employeeid", "-this-is-a-crappy-personal-number-that-does-not-match-the-regex-").
                get("known-user/profile").
                then().assertThat().body("personalNumber", equalTo(testValidPersonalNumber)).extract().response();

        // Still the correct employee ID?
        given().header("x-rp-grp", "z-gateleen-known-role,z-gateleen-admin").
                header("x-rp-usr", "known-user").
                get("known-user/profile").
                then().assertThat().body("personalNumber", equalTo(testValidPersonalNumber)).extract().response();

        async.complete();
    }
}
