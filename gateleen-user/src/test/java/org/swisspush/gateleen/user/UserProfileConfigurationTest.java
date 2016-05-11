package org.swisspush.gateleen.user;

import org.junit.Test;
import org.swisspush.gateleen.user.UserProfileConfiguration.ProfileProperty;

import java.util.Map;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;

/**
 * @author https://github.com/floriankammermann [Florian Kammermann] on 02.07.2015
 */
public class UserProfileConfigurationTest {

    @Test
    public void buildUserProfileConfiguration() {

        // ACT
        UserProfileConfiguration userProfileConfiguration = UserProfileConfiguration.create()
                .addProfileProperty(ProfileProperty.with("x-rp-department", "department").setValueToUseIfNoOtherValidValue("unknown").build())
                .addProfileProperty(ProfileProperty.with("x-rp-mail", "mail").setValueToUseIfNoOtherValidValue("unknown").build())
                .addProfileProperty(ProfileProperty.with("x-rp-employeeid", "personalNumber").setValueToUseIfNoOtherValidValue("unknown").build())
                .addProfileProperty(ProfileProperty.with("x-rp-usr", "username").build())
                .addProfileProperty(ProfileProperty.with("x-rp-displayname", "fullName").build())
            .build();

        Map<String, ProfileProperty> profileProperties = userProfileConfiguration.getProfileProperties();

        // ASSERT
        assertThat(profileProperties.size(), is(5));
        assertThat(profileProperties.get("x-rp-department").getHeaderName(), is("x-rp-department"));
        assertThat(profileProperties.get("x-rp-department").getProfileName(), is("department"));
        assertThat(profileProperties.get("x-rp-department").getValueToUseIfNoOtherValidValue(), is("unknown"));
        assertThat(profileProperties.get("x-rp-mail").getHeaderName(), is("x-rp-mail"));
        assertThat(profileProperties.get("x-rp-mail").getProfileName(), is("mail"));
        assertThat(profileProperties.get("x-rp-mail").getValueToUseIfNoOtherValidValue(), is("unknown"));
        assertThat(profileProperties.get("x-rp-employeeid").getHeaderName(), is("x-rp-employeeid"));
        assertThat(profileProperties.get("x-rp-employeeid").getProfileName(), is("personalNumber"));
        assertThat(profileProperties.get("x-rp-employeeid").getValueToUseIfNoOtherValidValue(), is("unknown"));
        assertThat(profileProperties.get("x-rp-usr").getHeaderName(), is("x-rp-usr"));
        assertThat(profileProperties.get("x-rp-usr").getProfileName(), is("username"));
        assertThat(profileProperties.get("x-rp-usr").getValueToUseIfNoOtherValidValue(), is((Object) null));
        assertThat(profileProperties.get("x-rp-displayname").getHeaderName(), is("x-rp-displayname"));
        assertThat(profileProperties.get("x-rp-displayname").getProfileName(), is("fullName"));
        assertThat(profileProperties.get("x-rp-displayname").getValueToUseIfNoOtherValidValue(), is((Object) null));
    }

    @Test
    public void validateValueNoRegexSet() {

        // ARRANGE
        UserProfileConfiguration userProfileConfiguration = UserProfileConfiguration.create()
                .addProfileProperty(ProfileProperty.with("x-rp-department", "department").setValueToUseIfNoOtherValidValue("unknown").build())
                .build();

        String department = "testdepartment";

        // ACT
        Boolean isDepartmentValid = userProfileConfiguration.getProfileProperties().get("x-rp-department").isValid(department);

        // ASSERT
        assertThat(isDepartmentValid, is(Boolean.TRUE));
    }

    @Test(expected=IllegalStateException.class)
    public void validateValueInvalidRegexSet() {

        // ACT
        UserProfileConfiguration.create()
                .addProfileProperty(ProfileProperty.with("x-rp-department", "department").validationRegex("***").build())
                .build();

    }

    @Test
    public void validateValueRegexNoMatch() {

        // ARRANGE
        UserProfileConfiguration userProfileConfiguration = UserProfileConfiguration.create()
                .addProfileProperty(ProfileProperty.with("x-rp-department", "department").validationRegex("\\d*").build())
                .build();

        String department = "testdepartment";

        // ACT
        Boolean isDepartmentValid = userProfileConfiguration.getProfileProperties().get("x-rp-department").isValid(department);

        // ASSERT
        assertThat(isDepartmentValid, is(Boolean.FALSE));
    }

    @Test
    public void validateValueRegexMatch() {

        // ARRANGE
        UserProfileConfiguration userProfileConfiguration = UserProfileConfiguration.create()
                .addProfileProperty(ProfileProperty.with("x-rp-employeeid", "employeeId").validationRegex("\\d*{8}").build())
                .build();

        String employeeid = "09000555";

        // ACT
        Boolean isEmployeeIdValid = userProfileConfiguration.getProfileProperties().get("x-rp-employeeid").isValid(employeeid);

        // ASSERT
        assertThat(isEmployeeIdValid, is(Boolean.TRUE));
    }

    @Test
    public void urlProfileMatch() {

        // ARRANGE
        UserProfileConfiguration userProfileConfiguration = UserProfileConfiguration.create().userProfileUriPattern("/server/users/v1/([^/]+)/profile").build();

        // ACT, ASSERT
        assertThat(userProfileConfiguration.doesUrlMatchTheProfileUriPattern("/server/users/v1/09000555/profile"), is(Boolean.TRUE));
        assertThat(userProfileConfiguration.doesUrlMatchTheProfileUriPattern("/server/users/v1/00000000/profile"), is(Boolean.TRUE));
        assertThat(userProfileConfiguration.doesUrlMatchTheProfileUriPattern("/server/users/v1/99999999/profile"), is(Boolean.TRUE));
        assertThat(userProfileConfiguration.doesUrlMatchTheProfileUriPattern("/server/users/v1/99999999/profil"), is(Boolean.FALSE));
        assertThat(userProfileConfiguration.doesUrlMatchTheProfileUriPattern("server/users/v1/99999999/profile"), is(Boolean.FALSE));
        assertThat(userProfileConfiguration.doesUrlMatchTheProfileUriPattern("/server/users/v2/99999999/profile"), is(Boolean.FALSE));

        assertThat(userProfileConfiguration.doesUrlMatchTheProfileUriPattern("/server/users/v1//profile"), is(Boolean.FALSE));
    }

    @Test
    public void urlUseridExtraction() {

        // ARRANGE
        UserProfileConfiguration userProfileConfiguration = UserProfileConfiguration.create().userProfileUriPattern("/server/users/v1/([^/]+)/profile").build();

        // ACT, ASSERT
        assertThat(userProfileConfiguration.extractUserIdFromProfileUri("/server/users/v1/09000555/profile"), is("09000555"));
        assertThat(userProfileConfiguration.extractUserIdFromProfileUri("/server/users/v1/00000000/profile"), is("00000000"));
        assertThat(userProfileConfiguration.extractUserIdFromProfileUri("/server/users/v1/99999999/profile"), is("99999999"));
        assertThat(userProfileConfiguration.extractUserIdFromProfileUri("/server/users/v1/99999999/profil"), is(nullValue()));
        assertThat(userProfileConfiguration.extractUserIdFromProfileUri("server/users/v1/99999999/profile"), is(nullValue()));
        assertThat(userProfileConfiguration.extractUserIdFromProfileUri("/server/users/v2/99999999/profile"), is(nullValue()));

    }

}
