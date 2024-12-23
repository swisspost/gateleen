package org.swisspush.gateleen.queue.queuing.circuitbreaker.util;

import javax.annotation.Nullable;
import java.util.Objects;
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
    private final String metricName;

    public PatternAndCircuitHash(@Nullable Pattern pattern, String circuitHash, @Nullable String metricName) {
        this.pattern = pattern;
        this.circuitHash = circuitHash;
        this.metricName = metricName;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public String getCircuitHash() {
        return circuitHash;
    }

    public String getMetricName() {
        return metricName;
    }

    @Override
    public String toString() {
        return "PatternAndCircuitHash{" +
                "pattern=" + pattern +
                ", circuitHash='" + circuitHash + '\'' +
                ", metricName='" + metricName + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PatternAndCircuitHash that = (PatternAndCircuitHash) o;
        return Objects.equals(pattern != null ? pattern.pattern() : null,
                that.pattern != null ? that.pattern.pattern() : null) &&
                circuitHash.equals(that.circuitHash);
    }

    @Override
    public int hashCode() {
        int result = pattern.hashCode();
        result = 31 * result + circuitHash.hashCode();
        return result;
    }
}
