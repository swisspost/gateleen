package org.swisspush.gateleen.security;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds a pattern and implements hashcode/equals to use in collections.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class PatternHolder {

    private Pattern pattern;
    private String patternStr;

    private static final Pattern WILDCARD_PATTERN = Pattern.compile("[<](.+?)[>]");
    public static final Logger log = LoggerFactory.getLogger(PatternHolder.class);
    private static final MultiMap EMPTY_HEADERS = new CaseInsensitiveHeaders();

    public PatternHolder(String patternStr) {
        super();
        if(containsWildcards(patternStr)){
            this.patternStr = patternStr;
        } else {
            this.pattern = Pattern.compile(patternStr);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PatternHolder that = (PatternHolder) o;

        if(pattern != null && that.pattern != null){
            return pattern.pattern().equals(that.pattern.pattern());
        } else if(pattern == null && that.pattern != null){
            return false;
        } else if(pattern != null){
            return false;
        }

        return patternStr != null ? patternStr.equals(that.patternStr) : that.patternStr == null;
    }

    @Override
    public int hashCode() {
        int result = pattern != null ? pattern.pattern().hashCode() : 0;
        result = 31 * result + (patternStr != null ? patternStr.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "PatternHolder{" +
                "pattern=" + pattern +
                ", patternStr='" + patternStr + '\'' +
                '}';
    }

    public Pattern getPattern(MultiMap headers) {
        if(patternStr != null){
            String replacedWildcards = replaceWildcards(patternStr, headers);
            return Pattern.compile(replacedWildcards);
        }
        return pattern;
    }

    public Pattern getPattern() {
        return getPattern(EMPTY_HEADERS);
    }

    private boolean containsWildcards(String patternStr){
        return !wildcards(patternStr).isEmpty();
    }

    private List<String> wildcards(String patternStr){
        List<String> wildcards = new ArrayList<>();
        Matcher matcher = WILDCARD_PATTERN.matcher(patternStr);
        while (matcher.find()) {
            String wildcard = matcher.group(1);
            wildcards.add(wildcard);
        }
        return wildcards;
    }

    private String replaceWildcards(String patternStr, MultiMap headers){
        List<String> wildcards = wildcards(patternStr);
        String replacePattern = patternStr;
        for (String wildcard : wildcards) {
            if(headers.get(wildcard) != null){
                replacePattern = replacePattern.replaceAll(Pattern.quote("<" + wildcard + ">"), headers.get(wildcard));
            } else {
                log.warn("No value for request header {} found. Not going to replace wildcard", wildcard);
            }
        }
        return replacePattern;
    }
}
