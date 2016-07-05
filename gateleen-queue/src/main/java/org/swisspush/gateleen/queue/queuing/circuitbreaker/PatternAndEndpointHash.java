package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import java.util.regex.Pattern;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class PatternAndEndpointHash {
    private final Pattern pattern;
    private final String endpointHash;

    public PatternAndEndpointHash(Pattern pattern, String endpointHash) {
        this.pattern = pattern;
        this.endpointHash = endpointHash;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public String getEndpointHash() {
        return endpointHash;
    }

    @Override
    public String toString() {
        return "url pattern: " + pattern.pattern() + " endpoint hash: " + endpointHash;
    }
}
