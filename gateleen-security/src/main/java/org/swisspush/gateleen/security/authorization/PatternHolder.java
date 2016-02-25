package org.swisspush.gateleen.security.authorization;

import java.util.regex.Pattern;

/**
 * Holds a pattern and implements hashcode/equals to use in collections.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class PatternHolder {

    private Pattern pattern;

    public PatternHolder(Pattern pattern) {
        super();
        this.pattern = pattern;
    }

    @Override
    public int hashCode() {
        return pattern.toString().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof PatternHolder) {
            return pattern.toString().equals(obj.toString());
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return pattern.toString();
    }

    public Pattern getPattern() {
        return pattern;
    }
}
