package org.swisspush.gateleen.user.user;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Encapsulates the information, which are used to create and update profiles.
 * This contains mainly the mapping of http request headers to profile attributes.
 *
 * @author https://github.com/floriankammermann [Florian Kammermann] on 02.07.2015
 */
public class UserProfileConfiguration {

    private Map<String,ProfileProperty> profileProperties = new HashMap<>();
    private List<String> allowedProfileProperties = new ArrayList<>();
    private Pattern userProfileUriPattern;
    private String roleProfilesRoot;
    private String rolePattern;

    private UserProfileConfiguration(UserProfileConfigurationBuilder arguments) {
        this.allowedProfileProperties = arguments.allowedProfileProperties;
        this.profileProperties = arguments.profileProperties;
        if(arguments.userProfileUriPattern != null) {
            this.userProfileUriPattern = Pattern.compile(arguments.userProfileUriPattern);
        }
        this.roleProfilesRoot = arguments.roleProfilesRoot;
        this.rolePattern = arguments.rolePattern;
    }

    public static UserProfileConfigurationBuilder create() {
        return new UserProfileConfigurationBuilder();
    }

    public Map<String, ProfileProperty> getProfileProperties() {
        return profileProperties;
    }

    public boolean isAllowedProfileProperty(String fieldName) {
        return allowedProfileProperties.contains(fieldName);
    }

    public boolean doesUrlMatchTheProfileUriPattern(String url) {
        return userProfileUriPattern.matcher(url).matches();
    }

    public String extractUserIdFromProfileUri(String url) {
        Matcher matcher = userProfileUriPattern.matcher(url);
        matcher.matches();
        try {
            return matcher.group(1);
        } catch (Exception e) {
            return null;
        }
    }

    public String getRoleProfilesRoot() {
        return roleProfilesRoot;
    }

    public String getRolePattern() {
        return rolePattern;
    }

    public enum UpdateStrategy {
        /**
         * Updates the value only if the value in the profile is invalid ({@link ProfileProperty#isValid(String)})
         * and there's a valid value in the header.
         */
        UPDATE_ONLY_IF_PROFILE_VALUE_IS_INVALID,
        /**
         * Always updates the profile value if the header value is valid ({@link ProfileProperty#isValid(String)})
         * and differs from the profile value.
         */
        UPDATE_ALWAYS
    }

    public static class ProfileProperty {
        private String headerName;
        private String profileName;
        private String validationRegex;
        private Pattern validationRegexPattern;
        private boolean optional;
        private UpdateStrategy updateStrategy;
        private String valueToUseIfNoOtherValidValue;

        private ProfileProperty(ProfilePropertyBuilder arguments) {
            this.headerName = arguments.headerName;
            this.profileName = arguments.profileName;
            this.validationRegex = arguments.validationRegex;
            this.optional = arguments.optional;
            this.updateStrategy = arguments.updateStrategy;
            this.valueToUseIfNoOtherValidValue = arguments.valueToUseIfNoOtherValidValue;
        }

        public static ProfilePropertyBuilder with(String headerName, String profileName) {
            return new ProfilePropertyBuilder(headerName, profileName);
        }

        public String getHeaderName() {
            return headerName;
        }

        public String getProfileName() {
            return profileName;
        }

        public String getValueToUseIfNoOtherValidValue() {
            return valueToUseIfNoOtherValidValue;
        }

        public String getValidationRegex() {
            return validationRegex;
        }

        public UpdateStrategy getUpdateStrategy() {
            return updateStrategy;
        }

        public boolean isOptional() {
            return optional;
        }

        /**
         * Value missing (<code>null</code>: Is valid if property optional.
         * Value not missing: Is valid if validation regex is missing
         * or if the value matches the validation regex.
         *
         * @param value The value or <code>null</code> if the property is missing.
         * @return Valid?
         */
        public boolean isValid(String value) {
            if (value == null) {
                return isOptional();
            } else {
                if (validationRegexPattern == null) {
                    return true;
                }
                return validationRegexPattern.matcher(value).matches();
            }
        }

        protected void compileRegex() {
            if(validationRegex == null) {
                return;
            }
            try {
                this.validationRegexPattern = Pattern.compile(validationRegex);
            } catch(PatternSyntaxException e) {
                throw new IllegalStateException("UserProfile - the validation regex for the profile attribute: " + profileName + " is not valid: " + validationRegex);
            }
        }

        public static class ProfilePropertyBuilder {
            public boolean optional;
            public UpdateStrategy updateStrategy = UpdateStrategy.UPDATE_ALWAYS;
            public String valueToUseIfNoOtherValidValue;
            private String headerName;
            private String profileName;
            private String validationRegex;

            protected ProfilePropertyBuilder(String headerName, String profileName) {
                this.headerName = headerName;
                this.profileName = profileName;
            }

            public ProfilePropertyBuilder validationRegex(String validationRegex) {
                this.validationRegex = validationRegex;
                return this;
            }

            /**
             * When to update the profile property from header; defaults to UPDATE_ALWAYS.
             */
            public ProfilePropertyBuilder setUpdateStrategy(UpdateStrategy updateStrategy) {
                this.updateStrategy = updateStrategy;
                return this;
            }

            /**
             * If optional is <code>true</code>, missing (<code>null</code>) values are always valid
             * values (regex is ignored in this case). If optional is <code>false</code>, <code>null</code>
             * values are always invalid (regex is ignored in this case). Defaults to <code>false</code>.
             */
            public ProfilePropertyBuilder setOptional(boolean optional) {
                this.optional = optional;
                return this;
            }

            /**
             * Optional (nullable). What value to use if there's no valid value in the profile and also no valid value
             * in the headers.
             */
            public ProfilePropertyBuilder setValueToUseIfNoOtherValidValue(String valueToUseIfNoOtherValidValue) {
                this.valueToUseIfNoOtherValidValue = valueToUseIfNoOtherValidValue;
                return this;
            }

            public ProfileProperty build() {
                ProfileProperty profileProperty = new ProfileProperty(this);
                profileProperty.compileRegex();
                return profileProperty;
            }
        }
    }

    public static class UserProfileConfigurationBuilder {
        private List<String> allowedProfileProperties = new ArrayList<>();
        private Map<String,ProfileProperty> profileProperties = new HashMap<>();
        private String userProfileUriPattern;
        private String roleProfilesRoot;
        private String rolePattern;

        public UserProfileConfigurationBuilder addAllowedProfileProperties(String... allowedProfileProperties) {
            if(allowedProfileProperties != null) {
                this.allowedProfileProperties = Arrays.asList(allowedProfileProperties);
            }
            return this;
        }

        public UserProfileConfigurationBuilder addProfileProperty(ProfileProperty profileProperty) {
            this.profileProperties.put(profileProperty.getHeaderName(),profileProperty);
            return this;
        }

        public UserProfileConfigurationBuilder userProfileUriPattern(String userProfileUriPattern) {
            this.userProfileUriPattern = userProfileUriPattern;
            return this;
        }

        public UserProfileConfigurationBuilder roleProfilesRoot(String roleProfilesRoot) {
            this.roleProfilesRoot = roleProfilesRoot;
            return this;
        }

        public UserProfileConfigurationBuilder rolePattern(String rolePattern) {
            this.rolePattern = rolePattern;
            return this;
        }

        public UserProfileConfiguration build() {
            return new UserProfileConfiguration(this);
        }
    }
}
