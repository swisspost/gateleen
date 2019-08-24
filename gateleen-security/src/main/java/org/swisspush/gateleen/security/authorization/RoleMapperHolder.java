package org.swisspush.gateleen.security.authorization;

import java.util.regex.Pattern;

/**
 * Holds a pattern and implements hashcode/equals to use in collections.
 *
 * @author https://github.com/kusig [Markus Guenther]
 */
public class RoleMapperHolder {

    /**
     * The regex pattern to match for a role mapping
     */
    private Pattern pattern;
    /**
     * The resulting role if the given regex pattern matches
     */
    private String role;

    /**
     * Defines if the originalRole must be kept or not in the list of roles to apply against the ACL
     */
    private Boolean keepOriginal;

    RoleMapperHolder(Pattern pattern, String role, Boolean keepOriginal) {
        super();
        this.pattern = pattern;
        this.role = role;
        this.keepOriginal = keepOriginal;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public void setPattern(Pattern pattern) {
        this.pattern = pattern;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getKeepOriginal() {
        return keepOriginal;
    }

    public void setKeepOriginal(Boolean keepOriginal) {
        this.keepOriginal = keepOriginal;
    }
}
