package org.swisspush.gateleen.user;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.json.JsonObject;
import org.junit.Test;
import org.slf4j.Logger;
import org.swisspush.gateleen.user.UserProfileConfiguration.ProfileProperty;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

/**
 * @author https://github.com/floriankammermann [Florian Kammermann] on 03.07.2015
 */
public class UserProfileManipulatorTest {

    private void createProfileWithLanguageCheck(String acceptLanguage, String xRpLang, String expectedLangInProfile) {

        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);

        MultiMap headers = new CaseInsensitiveHeaders();
        if(acceptLanguage != null) {
            headers.add("Accept-Language", acceptLanguage);
        }
        if(xRpLang != null) {
            headers.add("x-rp-lang", xRpLang);
        }
        JsonObject profile = userProfileManipulater.createProfileWithLanguage(headers);
        assertThat(profile.getString("lang"), is(expectedLangInProfile));
    }

    @Test
    public void createProfileWithLanguageAcceptLanguateDeInHeader() {

        // tests create only the accept language headers
        createProfileWithLanguageCheck("de-CH", null, "de");
        createProfileWithLanguageCheck("fr-CH", null, "fr");
        createProfileWithLanguageCheck("it-CH", null, "it");
        createProfileWithLanguageCheck("en-US", null, "en");
        createProfileWithLanguageCheck("es-MX", null, "de");

        // tests create the x-rp-lang header
        createProfileWithLanguageCheck("en-US", "D", "de");
        createProfileWithLanguageCheck("en-US", "F", "fr");
        createProfileWithLanguageCheck("en-US", "I", "it");
        createProfileWithLanguageCheck("en-US", "E", "en");
        createProfileWithLanguageCheck("en-US", "X", "de");
    }

    @Test
    public void createInitialProfileAllHeadersSet() {

        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);

        MultiMap headers = new CaseInsensitiveHeaders();
        headers.add("Accept-Language", "en-US");
        headers.add("x-rp-lang", "D");
        headers.add("x-rp-usr", "04146251");
        headers.add("x-rp-department", "department 1");
        headers.add("x-rp-mail", "jane.doe@swisspush.org");
        headers.add("x-rp-employeeid", "04146251");
        headers.add("x-rp-displayname", "Jane Doe");

        List<ProfileProperty> profileProperties =
                Arrays.asList(ProfileProperty.with("x-rp-department", "department").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-mail", "mail").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-employeeid", "personalNumber").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-usr", "username").build(),
                        ProfileProperty.with("x-rp-displayname", "fullName").build());

        JsonObject initialProfile = userProfileManipulater.createInitialProfile(headers, "04146251", profileProperties);

        assertThat(initialProfile.getString("lang"), is("de"));
        assertThat(initialProfile.getString("username"), is("04146251"));
        assertThat(initialProfile.getString("department"), is("department 1"));
        assertThat(initialProfile.getString("mail"), is("jane.doe@swisspush.org"));
        assertThat(initialProfile.getString("personalNumber"), is("04146251"));
        assertThat(initialProfile.getString("fullName"), is("Jane Doe"));
    }

    @Test
    public void createInitialProfileNoHeadersSet() {

        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);

        MultiMap headers = new CaseInsensitiveHeaders();

        List<ProfileProperty> profileProperties =
                Arrays.asList(ProfileProperty.with("x-rp-department", "department").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-mail", "mail").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-employeeid", "personalNumber").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-usr", "username").build(),
                        ProfileProperty.with("x-rp-displayname", "fullName").build());

        JsonObject initialProfile = userProfileManipulater.createInitialProfile(headers, "04146251", profileProperties);

        assertThat(initialProfile.getString("lang"), is("de"));
        assertThat(initialProfile.getString("department"), is("unknown"));
        assertThat(initialProfile.getString("mail"), is("unknown"));
        assertThat(initialProfile.getString("personalNumber"), is("unknown"));
        assertThat(initialProfile.getString("username"), is(nullValue()));
        assertThat(initialProfile.getString("fullName"), is(nullValue()));
    }

    @Test
    public void enrichProfile() {

        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);

        MultiMap headers = new CaseInsensitiveHeaders();
        headers.add("Accept-Language", "en-US");
        headers.add("x-rp-lang", "D");
        headers.add("x-rp-usr", "04146251");
        headers.add("x-rp-department", "department 1");
        headers.add("x-rp-mail", "jane.doe@swisspush.org");
        headers.add("x-rp-employeeid", "04146251");
        headers.add("x-rp-displayname", "Jane Doe");

        List<ProfileProperty> profileProperties =
                Arrays.asList(ProfileProperty.with("x-rp-department", "department").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-mail", "mail").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-employeeid", "personalNumber").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-usr", "username").build(),
                        ProfileProperty.with("x-rp-displayname", "fullName").build());

        JsonObject profile = new JsonObject();
        profile.put("lang", "fr");
        userProfileManipulater.enrichProfile(headers, profile, "04146251", profileProperties);

        // the language is not reset if the profile already exists
        assertThat(profile.getString("lang"), is("fr"));
        assertThat(profile.getString("username"), is("04146251"));
        assertThat(profile.getString("department"), is("department 1"));
        assertThat(profile.getString("mail"), is("jane.doe@swisspush.org"));
        assertThat(profile.getString("personalNumber"), is("04146251"));
        assertThat(profile.getString("fullName"), is("Jane Doe"));
    }

    @Test
    public void enrichProfileInvalidEmployeeId() {

        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);

        MultiMap headers = new CaseInsensitiveHeaders();
        headers.add("Accept-Language", "en-US");
        headers.add("x-rp-lang", "D");
        headers.add("x-rp-usr", "04146251");
        headers.add("x-rp-department", "department 1");
        headers.add("x-rp-mail", "jane.doe@swisspush.org");
        headers.add("x-rp-employeeid", "(null)");
        headers.add("x-rp-displayname", "Jane Doe");

        List<ProfileProperty> profileProperties =
                Arrays.asList(ProfileProperty.with("x-rp-department", "department").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-mail", "mail").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-employeeid", "personalNumber").validationRegex("\\d*{8}").setValueToUseIfNoOtherValidValue("unknown").build(),
                        ProfileProperty.with("x-rp-usr", "username").build(),
                        ProfileProperty.with("x-rp-displayname", "fullName").build());

        JsonObject profile = new JsonObject();
        profile.put("lang", "fr");
        profile.put("username", "04146251");
        profile.put("department", "department 1");
        profile.put("mail", "jane.doe@swisspush.org");
        profile.put("personalNumber", "04146251");
        profile.put("fullName", "Jane Doe");
        userProfileManipulater.enrichProfile(headers, profile, "04146251", profileProperties);

        // the language is not reset if the profile already exists
        assertThat(profile.getString("lang"), is("fr"));
        assertThat(profile.getString("username"), is("04146251"));
        assertThat(profile.getString("department"), is("department 1"));
        assertThat(profile.getString("mail"), is("jane.doe@swisspush.org"));
        assertThat(profile.getString("personalNumber"), is("04146251"));
        assertThat(profile.getString("fullName"), is("Jane Doe"));
    }

    @Test
    public void testUpdateStrategy() {
        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);

        MultiMap headers = new CaseInsensitiveHeaders();
        headers.set("x-rp-usr", "44444444");

        JsonObject profile = new JsonObject();

        boolean updated;

        // Update always from headers if there are 2 valid values
        headers.set("a", "value-from-headers");
        profile.put("b", "value-from-profile");
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).build());
        assertThat(updated, is(true));
        assertThat(profile.getString("b"), is("value-from-headers"));

        // Cannot update from headers, since the value there is invalid
        headers.remove("a");
        profile.put("b", "value-from-profile");
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).build());
        assertThat(updated, is(false));
        assertThat(profile.getString("b"), is("value-from-profile"));

        // Do not update with this update strategy (since the profile already has a valid value)
        headers.set("a", "value-from-headers");
        profile.put("b", "value-from-profile");
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ONLY_IF_PROFILE_VALUE_IS_INVALID).build());
        assertThat(updated, is(false));
        assertThat(profile.getString("b"), is("value-from-profile"));

        // If course update if the profile value is invalid
        headers.set("a", "value-from-headers");
        profile.remove("b");
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ONLY_IF_PROFILE_VALUE_IS_INVALID).build());
        assertThat(updated, is(true));
        assertThat(profile.getString("b"), is("value-from-headers"));
    }

    @Test
    public void testUseFallbackValue() {
        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);

        MultiMap headers = new CaseInsensitiveHeaders();
        headers.set("x-rp-usr", "44444444");

        JsonObject profile = new JsonObject();

        boolean updated;

        // Use a fallback value if both values are invalid
        headers.set("a", "invalid-value-in-headers");
        profile.put("b", "invalid-value-in-profile");
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").validationRegex("WILL_NOT_MATCH").
                        setValueToUseIfNoOtherValidValue("fallback").build());
        assertThat(updated, is(true));
        assertThat(profile.getString("b"), is("fallback"));

        // Never use fallback if another value is valid (in this case: profile value)
        headers.set("a", "invalid-value-in-headers");
        profile.put("b", "WILL_NOT_MATCH");
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").validationRegex("WILL_NOT_MATCH").
                        setValueToUseIfNoOtherValidValue("fallback").build());
        assertThat(updated, is(false)); // No update of the profile (the valid value is already in the profile)
        assertThat(profile.getString("b"), is("WILL_NOT_MATCH"));

        // Never use fallback if another value is valid (in this case: header value)
        headers.set("a", "WILL_NOT_MATCH");
        profile.put("b", "invalid-value-in-profile");
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").validationRegex("WILL_NOT_MATCH").
                        setValueToUseIfNoOtherValidValue("fallback").build());
        assertThat(updated, is(true));
        assertThat(profile.getString("b"), is("WILL_NOT_MATCH"));
    }

    @Test
    public void testOptionalProfileValues() {
        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);

        MultiMap headers = new CaseInsensitiveHeaders();
        headers.set("x-rp-usr", "44444444");

        JsonObject profile = new JsonObject();

        boolean updated;

        // In this case the value is removed from the profile (since the header has no such value and it's a optional value)
        headers.remove("a");
        profile.put("b", "I'm a value in the profile that's going to be removed");
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").validationRegex("REGEX_IS_IGNORED_IN_THIS_CASE").
                        setOptional(true).build());
        assertThat(updated, is(true));
        assertThat(profile.getString("b"), is((Object) null));

        // Will also not update the profile value, even if missing
        headers.set("a", "This is a valid value - but not updated, since already valid in value");
        profile.remove("b"); // This is a valid value ("null").
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").setOptional(true).
                        setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ONLY_IF_PROFILE_VALUE_IS_INVALID).build());
        assertThat(updated, is(false));
        assertThat(profile.getString("b"), is((Object) null));

        // But if update strategy is ALWAYS... update anyway, since the value in the header is valid too
        headers.set("a", "header-value");
        profile.remove("b"); // This is a valid value ("null").
        updated = userProfileManipulater.updateField("44444444", headers, profile,
                ProfileProperty.with("a", "b").setOptional(true).
                        setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).build());
        assertThat(updated, is(true));
        assertThat(profile.getString("b"), is("header-value"));
    }

    @Test
    public void testWrongXRpUsr() {
        Logger log = mock(Logger.class);
        UserProfileManipulater userProfileManipulater = new UserProfileManipulater(log);

        MultiMap headers = new CaseInsensitiveHeaders();
        headers.set("x-rp-usr", "44444444");

        JsonObject profile = new JsonObject();

        boolean updated;

        // Both values are valid, usually this'd take the value from header - but since the userId does not match, keeps the value from profile
        headers.set("a", "valid-value-in-header");
        profile.put("b", "valid-value-in-profile");
        updated = userProfileManipulater.updateField("66666666", headers, profile,
                ProfileProperty.with("a", "b").
                        setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).build());
        assertThat(updated, is(false));
        assertThat(profile.getString("b"), is("valid-value-in-profile"));

        // Value in profile if invalid - even in this case MUST NOT take the value from header (will have no value)
        headers.set("a", "valid-value-in-header");
        profile.remove("b"); // Invalid value
        updated = userProfileManipulater.updateField("66666666", headers, profile,
                ProfileProperty.with("a", "b").
                        setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).build());
        assertThat(updated, is(false));
        assertThat(profile.getString("b"), is((Object) null));

        // Value in profile if invalid - even in this case MUST NOT take the value from header (will use the fallback value)
        headers.set("a", "valid-value-in-header");
        profile.remove("b"); // Invalid value
        updated = userProfileManipulater.updateField("66666666", headers, profile,
                ProfileProperty.with("a", "b").
                        setUpdateStrategy(UserProfileConfiguration.UpdateStrategy.UPDATE_ALWAYS).
                        setValueToUseIfNoOtherValidValue("fallback-value").build());
        assertThat(updated, is(true));
        assertThat(profile.getString("b"), is("fallback-value"));
    }
}
