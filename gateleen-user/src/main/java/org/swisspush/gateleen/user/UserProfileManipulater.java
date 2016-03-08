package org.swisspush.gateleen.user;

import com.google.common.base.Objects;
import org.slf4j.Logger;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

import java.util.Collection;

/**
 * @author https://github.com/floriankammermann [Florian Kammermann] on 02.07.2015
 */
public class UserProfileManipulater {

    private Logger log;

    public UserProfileManipulater(Logger log) {
        this.log = log;
    }

    public JsonObject createInitialProfile(MultiMap headers, String userId, Collection<UserProfileConfiguration.ProfileProperty> profileProperties) {
        JsonObject profile = createProfileWithLanguage(headers);
        enrichProfile(headers, profile, userId, profileProperties);
        return profile;
    }

    /**
     * Calls updateField for each property.
     *
     * @return Update count (0 to len(profileProperties)).
     */
    protected int enrichProfile(MultiMap headers, JsonObject profile, String userId, Collection<UserProfileConfiguration.ProfileProperty> profileProperties) {
        int updateCount = 0;
        for (UserProfileConfiguration.ProfileProperty profileProperty : profileProperties) {
            if (updateField(userId, headers, profile, profileProperty)) {
                updateCount++;
            }
        }
        return updateCount;
    }

    /**
     * Updates a single field of the profile. Only updates the field if optional to (non-equal) and requested by
     * the update strategy {@link UserProfileConfiguration.ProfileProperty#getUpdateStrategy()} and the header value is valid.
     *
     * @return <code>true</code>: Field updated. <code>false</code>: Not updated.
     */
    public boolean updateField(String userId, MultiMap headers, JsonObject profile, UserProfileConfiguration.ProfileProperty profileProperty) {
        // Value in header
        final String valueInHeader;
        if (headers.contains(profileProperty.getHeaderName())) {
            valueInHeader = headers.get(profileProperty.getHeaderName());
        } else {
            valueInHeader = null;
        }
        final boolean isValidValueInHeader = profileProperty.isValid(valueInHeader);

        // Value in profile
        final String valueInProfile;
        if (profile.containsKey(profileProperty.getProfileName())) {
            valueInProfile = profile.getString(profileProperty.getProfileName());
        } else {
            valueInProfile = null;
        }
        final boolean isValidValueInProfile = profileProperty.isValid(valueInProfile);

        // Does x-rp-usr match?
        final boolean xRpUsrMatch =
                (userId != null && headers.contains("x-rp-usr") && userId.equals(headers.get("x-rp-usr")));

        if (!isValidValueInHeader && valueInHeader != null) {
            log.error("UserProfileManipulator - got the header attribute "
                    + "[" + profileProperty.getHeaderName() + "," + headers.get(profileProperty.getHeaderName()) + "], "
                    + "according the validation regex " + "[" + profileProperty.getValidationRegex() + "] "
                    + "this is not a valid value, we don't set it into the profile");
        }

        // Decision what value to use
        final String valueToUse;
        if (xRpUsrMatch) {
            // Ok, headers are from the same user.
            if (isValidValueInHeader) {
                // Ok, there's a valid value in headers.
                if (isValidValueInProfile) {
                    // So in this case there's a valid value in headers and also in the profile.
                    // ... now depends on the update strategy.
                    switch (profileProperty.getUpdateStrategy()) {
                        case UPDATE_ALWAYS:
                            // Yes, update from header
                            valueToUse = valueInHeader;
                            break;
                        case UPDATE_ONLY_IF_PROFILE_VALUE_IS_INVALID:
                            // No, update. The value in profile is already valid.
                            valueToUse = valueInProfile;
                            break;
                        default:
                            log.error("Invalid update strategy: "
                                    + profileProperty.getUpdateStrategy() + ". Assume UPDATE_ALWAYS.");
                            valueToUse = valueInHeader;
                            break;
                    }
                } else {
                    // Only header has a valid value. Easy decision. Take that.
                    valueToUse = valueInHeader;
                }
            } else {
                // Cannot update from headers - so have to take the value from profile (or the fallback value)
                if (isValidValueInProfile) {
                    // If valid, take the value from profile
                    valueToUse = valueInProfile;
                } else {
                    // If not valid, take the 'default' value.
                    valueToUse = profileProperty.getValueToUseIfNoOtherValidValue();
                }
            }
        } else {
            // Do never take the value from headers in this case (since it's the headers for a different user)
            if (isValidValueInProfile) {
                // If valid, take the value from profile
                valueToUse = valueInProfile;
            } else {
                // If not valid, take the 'default' value.
                valueToUse = profileProperty.getValueToUseIfNoOtherValidValue();
            }
        }

        // Is there really a change (vs. the value in profile)?
        final boolean hasRealChange = !Objects.equal(valueToUse, valueInProfile);
        if (hasRealChange) {
            if (valueToUse != null) {
                profile.put(profileProperty.getProfileName(), valueToUse);
            } else {
                profile.remove(profileProperty.getProfileName());
            }
            return true;
        } else {
            // No change
            return false;
        }
    }

    protected JsonObject createProfileWithLanguage(MultiMap headers) {
        final String lang;
        if (headers.contains("x-rp-lang")) {
            lang = getLang(headers);
        } else if (headers.contains("Accept-Language")) {
            String accepted = headers.get("Accept-Language").split(",")[0].split(";")[0].split("-")[0].toLowerCase();
            if (accepted.equals("de") || accepted.equals("fr") || accepted.equals("it") || accepted.equals("en")) {
                lang = accepted;
            } else {
                lang = "de";
            }
        } else {
            lang = "de";
        }

        JsonObject profile = new JsonObject();
        profile.put("lang", lang);

        return profile;
    }

    public static String getLang(MultiMap headers) {
        String lang;
        switch (headers.get("x-rp-lang")) {
            case "D":
                lang = "de";
                break;
            case "F":
                lang = "fr";
                break;
            case "I":
                lang = "it";
                break;
            case "E":
                lang = "en";
                break;
            default:
                lang = "de";
        }
        return lang;
    }
}
