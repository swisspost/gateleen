package org.swisspush.gateleen.kafka;

import java.util.Map;
import java.util.regex.Pattern;

public class KafkaConfiguration {

    private final Pattern topic;
    private final Map<String, String> configurations;

    KafkaConfiguration(Pattern topic, Map<String, String> configurations) {
        this.topic = topic;
        this.configurations = configurations;
    }

    Pattern getTopic() {
        return topic;
    }

    Map<String, String> getConfigurations() {
        return configurations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        KafkaConfiguration that = (KafkaConfiguration) o;

        if (!topic.pattern().equals(that.topic.pattern())) return false;
        return configurations.equals(that.configurations);

    }

    @Override
    public int hashCode() {
        int result = topic.hashCode();
        result = 31 * result + configurations.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "KafkaConfiguration{" +
                "topic=" + topic.pattern() +
                ", configurations=" + configurations +
                '}';
    }
}
