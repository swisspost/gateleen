package org.swisspush.gateleen.security.authorization;

import com.sun.istack.internal.NotNull;

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
    private final Pattern pattern;
    /**
     * The resulting role if the given regex pattern matches
     */
    private final String role;

    /**
     * Defines if the originalRole must be kept or not in the list of roles to apply against the ACL
     */
    private final boolean keepOriginal;


    /**
     * Holds the attributes needed to map roles. All params must be non null.
     *
     * @param pattern      The regular expression pattern to be applied to the incoming rules
     * @param role         The resulting mapped role
     * @param keepOriginal If true, the original role is kept and the mapped one added additionally
     */
    RoleMapperHolder(@NotNull Pattern pattern, @NotNull String role, boolean keepOriginal) {
        this.pattern = pattern;
        this.role = role;
        this.keepOriginal = keepOriginal;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public String getRole() {
        return role;
    }

    public boolean getKeepOriginal() {
        return keepOriginal;
    }

}
