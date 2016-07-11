package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import java.util.regex.Pattern;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class PatternAndCircuitHash {
    private final Pattern pattern;
    private final String circuitHash;

    public PatternAndCircuitHash(Pattern pattern, String circuitHash) {
        this.pattern = pattern;
        this.circuitHash = circuitHash;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public String getCircuitHash() {
        return circuitHash;
    }

    @Override
    public String toString() {
        return "url pattern: " + pattern.pattern() + " circuit hash: " + circuitHash;
    }
}
