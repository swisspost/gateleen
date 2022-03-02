package org.swisspush.gateleen.user;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;

import io.vertx.core.json.JsonObject;
import org.junit.Test;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.storage.ResourceStorage;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author https://github.com/floriankammermann [Florian Kammermann] on 10.04.2015
 */
public class UserProfileHandlerTest {


    @Test
    public void testCleanupProfileDefaultAttributes() {

        // Arrange
        Vertx vertx = mock(Vertx.class);
        when(vertx.eventBus()).thenReturn(mock(EventBus.class));
        ResourceStorage resourceStorage = mock(ResourceStorage.class);

        UserProfileConfiguration userProfileConfiguration =
                UserProfileConfiguration.create()
                        .userProfileUriPattern("/users/v1/([^/]+)/profile")
                        .addAllowedProfileProperties("username", "personalNumber", "fullname", "mail", "department", "lang",
                                "addAttr1", "addAttr2", "addAttr3")
                        .rolePattern("^z-gateleen[-_](.*)$")
                        .build();

        UserProfileHandler userProfileHandler = new UserProfileHandler(vertx, resourceStorage, userProfileConfiguration);

        JsonObject profile = new JsonObject();
        profile.put("personalNumber", "04146251");
        profile.put("username", "john");
        profile.put("mail", "john.doe@swisspush.org");
        profile.put("department", "sales");
        profile.put("lang", "de");
        profile.put("additionalAttribute", "addVal");

        // Act
        userProfileHandler.cleanupUserProfile(profile, updatedProfile -> {

            // Assert
            assertThat(updatedProfile.getString("personalNumber"), is("04146251"));
            assertThat(updatedProfile.getString("username"), is("john"));
            assertThat(updatedProfile.getString("mail"), is("john.doe@swisspush.org"));
            assertThat(updatedProfile.getString("department"), is("sales"));
            assertThat(updatedProfile.getString("lang"), is("de"));
            assertThat(updatedProfile.getString("additionalAttribute"), is(nullValue()));
        });

    }

    @Test
    public void testCleanupProfileAdditionalAttributes() {

        // Arrange
        Vertx vertx = mock(Vertx.class);
        when(vertx.eventBus()).thenReturn(mock(EventBus.class));
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        UserProfileConfiguration userProfileConfiguration =
                UserProfileConfiguration.create()
                        .userProfileUriPattern("/users/v1/([^/]+)/profile")
                        .addAllowedProfileProperties("username", "personalNumber", "fullname", "mail", "department", "lang",
                                "addAttr1", "addAttr2", "addAttr3")
                        .rolePattern("^z-gateleen[-_](.*)$")
                        .build();


        UserProfileHandler userProfileHandler = new UserProfileHandler(vertx, resourceStorage, userProfileConfiguration);

        JsonObject profile = new JsonObject();
        profile.put("personalNumber", "04146251");
        profile.put("username", "john");
        profile.put("mail", "john.doe@swisspush.org");
        profile.put("department", "sales");
        profile.put("lang", "de");
        profile.put("addAttr1", "addVal1");
        profile.put("addAttr2", "addVal2");
        profile.put("addAttr3", "addVal3");
        profile.put("addAttr4", "addVal4");

        // Act
        userProfileHandler.cleanupUserProfile(profile, updatedProfile -> {

            // Assert
            assertThat(updatedProfile.getString("personalNumber"), is("04146251"));
            assertThat(updatedProfile.getString("username"), is("john"));
            assertThat(updatedProfile.getString("mail"), is("john.doe@swisspush.org"));
            assertThat(updatedProfile.getString("department"), is("sales"));
            assertThat(updatedProfile.getString("lang"), is("de"));
            assertThat(updatedProfile.getString("addAttr1"), is("addVal1"));
            assertThat(updatedProfile.getString("addAttr2"), is("addVal2"));
            assertThat(updatedProfile.getString("addAttr3"), is("addVal3"));
            assertThat(updatedProfile.getString("addAttr4"), is(nullValue()));
        });

    }

    @Test
    public void updateUserprofileFieldSetToUnknownIfMissing() {

        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);
        JsonObject profile = new JsonObject();

        UserProfileConfiguration.ProfileProperty profileProperty =
                UserProfileConfiguration.ProfileProperty.with("x-rp-employeeid", "personalNumber")
                .validationRegex("\\d*{8}")
                        .setValueToUseIfNoOtherValidValue("unknown").build();

        // personalNumber exists in profile no header
        profile.put("personalNumber", "09000555");
        userProfileManipulater.updateField("09000555", createHeadersWithUserId09000555(), profile, profileProperty);
        assertThat(profile.getString("personalNumber"), is("09000555"));

        // personalNumber doesnt exist in profile no header
        profile.remove("personalNumber");
        userProfileManipulater.updateField("09000555", createHeadersWithUserId09000555(), profile, profileProperty);
        assertThat(profile.getString("personalNumber"), is("unknown"));

        // personalNumber exists in profile no header
        profile.put("personalNumber", "09000555");
        userProfileManipulater.updateField("09000555", createHeadersWithUserId09000555("09000555"), profile, profileProperty);
        assertThat(profile.getString("personalNumber"), is("09000555"));

        // personalNumber exists in profile header is null
        profile.put("personalNumber", "09000555");
        userProfileManipulater.updateField("09000555", createHeadersWithUserId09000555(""), profile, profileProperty);
        assertThat(profile.getString("personalNumber"), is(""));
    }

    @Test
    public void updateUserprofileFieldDontSetToUnknownIfMissing() {

        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);
        JsonObject profile = new JsonObject();

        UserProfileConfiguration.ProfileProperty profileProperty =
                UserProfileConfiguration.ProfileProperty.with("x-rp-employeeid", "personalNumber")
                        .validationRegex("\\d*{8}").build();

        // personalNumber exists in profile no header
        profile.put("personalNumber", "09000555");
        userProfileManipulater.updateField("09000555", createHeadersWithUserId09000555(), profile, profileProperty);
        assertThat(profile.getString("personalNumber"), is("09000555"));

        // personalNumber doesnt exist in profile no header
        profile.remove("personalNumber");
        userProfileManipulater.updateField("09000555", createHeadersWithUserId09000555(), profile, profileProperty);
        assertThat(profile.containsKey("personalNumber"), is(Boolean.FALSE));

        // personalNumber exists in profile no header
        profile.put("personalNumber", "09000555");
        userProfileManipulater.updateField("09000555", createHeadersWithUserId09000555("09000555"), profile, profileProperty);
        assertThat(profile.getString("personalNumber"), is("09000555"));

        // personalNumber exists in profile header is null
        profile.put("personalNumber", "09000555");
        userProfileManipulater.updateField("09000555", createHeadersWithUserId09000555(""), profile, profileProperty);
        assertThat(profile.getString("personalNumber"), is(""));
    }

    private MultiMap createHeadersWithUserId09000555() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("x-rp-usr", "09000555");
        return headers;
    }

    private MultiMap createHeadersWithUserId09000555(String employeeId) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("x-rp-usr", "09000555");
        headers.add("x-rp-employeeid", employeeId);
        return headers;
    }


}
