package org.swisspush.gateleen.security.content;

import org.swisspush.gateleen.security.PatternHolder;

import java.util.List;

public class ContentTypeConstraint {

    private final PatternHolder urlPattern;
    private final List<PatternHolder> allowedTypes;

    public ContentTypeConstraint(PatternHolder urlPattern, List<PatternHolder> allowedTypes) {
        this.urlPattern = urlPattern;
        this.allowedTypes = allowedTypes;
    }

    public PatternHolder urlPattern() {
        return urlPattern;
    }

    public List<PatternHolder> allowedTypes() {
        return allowedTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ContentTypeConstraint that = (ContentTypeConstraint) o;

        if (!urlPattern.equals(that.urlPattern)) return false;
        return allowedTypes.equals(that.allowedTypes);
    }

    @Override
    public int hashCode() {
        int result = urlPattern.hashCode();
        result = 31 * result + allowedTypes.hashCode();
        return result;
    }
}
