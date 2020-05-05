package org.swisspush.gateleen.security.authorization;


import javax.annotation.Nonnull;
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
     * Defines if the originalRole will be distributed further
     */
    private final boolean keepOriginalRole;

    /**
     * Defines if the final (last matched) resultingRole will be distributed further. Therefore this is
     * only relevant if continueMapping==false
     */
    private final boolean keepResultingRole;


    /**
     * Defines if the Mapping must be continued after this mapping rule or not.
     */
    private final boolean continueMapping;

    /**
     * Holds the attributes needed to map roles. All params must be non null.
     *
     * @param pattern           The regular expression pattern to be applied to the incoming rules, must not be null
     * @param role              The resulting mapped role, must not be null
     * @param keepOriginalRole  If true, the original role is kept and distributed further
     * @param keepResultingRole If true, the resulting (matched) role is kept and distributed further for the last matched role.
     *                          This is only relevant if continueMapping is false of course.
     * @param continueMapping   If true, the mapping processing will not be stopped with this mapper if it matches but it just
     *                          continues with the next one working with the mapped role already. Thus there might be possible
     *                          a chain of mappings eg. domain-admin-int  -> domain-admin -> domain   So at the end only
     *                          the ACL for "domain" is used as the final resulting role from mapping.
     */
    RoleMapperHolder(@Nonnull Pattern pattern, @Nonnull String role, boolean keepOriginalRole, boolean continueMapping, boolean keepResultingRole) {
        this.pattern = pattern;
        this.role = role;
        this.keepOriginalRole = keepOriginalRole;
        this.keepResultingRole = keepResultingRole;
        this.continueMapping = continueMapping;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public String getRole() {
        return role;
    }

    public boolean getKeepOriginalRole() {
        return keepOriginalRole;
    }

    public boolean getKeepResultingRole() {
        return keepResultingRole;
    }

    public boolean getContinueMapping() {
        return continueMapping;
    }

}
