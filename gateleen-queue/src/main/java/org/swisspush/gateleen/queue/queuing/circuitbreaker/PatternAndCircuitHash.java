package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import java.util.regex.Pattern;

/**
 * Container class to hold a pattern (based on the name of the routing rule) and the hash representation of this pattern.
 * The hash representation of the pattern is used as unique identifier of a circuit.
 *
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PatternAndCircuitHash that = (PatternAndCircuitHash) o;

        if (!pattern.pattern().equals(that.pattern.pattern())) return false;
        return circuitHash.equals(that.circuitHash);

    }

    @Override
    public int hashCode() {
        int result = pattern.hashCode();
        result = 31 * result + circuitHash.hashCode();
        return result;
    }
}
