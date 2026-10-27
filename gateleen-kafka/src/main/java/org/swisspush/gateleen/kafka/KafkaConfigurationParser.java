package org.swisspush.gateleen.kafka;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Parses the kafka topic configuration resource into a list of {@link KafkaConfiguration}s
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class KafkaConfigurationParser {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfigurationParser.class);

    private static final AtomicInteger instanceIndexCounter = new AtomicInteger(0);

    /**
     * Parses the provided kafka topic configuration resource and returns a list of {@link KafkaConfiguration} objects.
     *
     * Parsing errors reject the entire configuration with an {@link IllegalArgumentException}; a partial list of
     * topic configurations is never returned. An empty JSON object is a valid configuration with no topic entries.
     *
     * @param configurationResourceBuffer the resource to parse
     * @return a list of {@link KafkaConfiguration} objects
     */
    static List<KafkaConfiguration> parse(Buffer configurationResourceBuffer, Map<String, Object> properties) {

        final String source;
        try {
            source = configurationResourceBuffer.toString(UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Could not read Kafka configuration; rejecting entire Kafka configuration", e);
        }

        final String replacedConfig;
        try {
            replacedConfig = StringUtils.replaceWildcardConfigs(source,
                    properties == null ? Collections.emptyMap() : properties);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Could not resolve Kafka configuration placeholders; rejecting entire Kafka configuration", e);
        }

        final JsonObject config;
        try {
            config = new JsonObject(Buffer.buffer(replacedConfig));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Could not parse Kafka configuration as a JSON object; rejecting entire Kafka configuration", e);
        }

        List<KafkaConfiguration> configurations = new ArrayList<>();
        for (String topicPattern : config.fieldNames()) {
            final Pattern pattern;
            try {
                pattern = Pattern.compile(topicPattern);
            } catch (PatternSyntaxException patternException) {
                throw new IllegalArgumentException("Invalid topic regex pattern '" + topicPattern + "'; rejecting entire Kafka configuration",
                        patternException);
            }

            Object topicValue = config.getValue(topicPattern);
            if (!(topicValue instanceof JsonObject)) {
                throw new IllegalArgumentException("Topic entry '" + topicPattern + "' must be a JSON object; rejecting entire Kafka configuration");
            }

            final Map<String, String> additionalConfig;
            try {
                additionalConfig = extractAdditionalConfig((JsonObject) topicValue, topicPattern);
                setUniqueClientId(additionalConfig);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Could not parse topic entry '" + topicPattern
                        + "'; rejecting entire Kafka configuration", e);
            }
            configurations.add(new KafkaConfiguration(pattern, additionalConfig));
            log.info("Topic '{}' successfully parsed and added to kafka configuration list", topicPattern);
        }

        return configurations;
    }

    /**
     * Adds a postfix that is unique on a per vert.x cluster node to the client.id if actually configured. This is relevant in a case 
     * where we have multiple {@link KafkaHandler} instances.
     * 
     * See https://stackoverflow.com/questions/40880832/instancealreadyexistsexception-coming-from-kafka-consumer
     */
    private static void setUniqueClientId(Map<String, String> config) {
        String clientId = config.get("client.id");
        if (StringUtils.isNotEmpty(clientId)) {
            config.put("client.id", clientId + "-" + instanceIndexCounter.incrementAndGet());
        }
    }

    private static Map<String, String> extractAdditionalConfig(JsonObject topicObject, String topicPattern){
        Map<String, String> additionalConfigs = new HashMap<>();
        for (String configKey : topicObject.fieldNames()) {
            Object configValue = topicObject.getValue(configKey);
            if (!(configValue instanceof String)) {
                throw new IllegalArgumentException("Topic entry '" + topicPattern + "' property '" + configKey
                        + "' must be a string; rejecting entire Kafka configuration");
            }
            additionalConfigs.put(configKey, (String) configValue);
        }
        return additionalConfigs;
    }
}
