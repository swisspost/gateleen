package org.swisspush.gateleen.kafka;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses the kafka topic configuration resource into a list of {@link KafkaConfiguration}s
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class KafkaConfigurationParser {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfigurationParser.class);

    /**
     * Parses the provided kafka topic configuration resource and returns a list of {@link KafkaConfiguration} objects.
     *
     * When the configuration resource contains invalid regex patterns, a warning will be logged and the corresponding
     * {@link KafkaConfiguration} object will not be included in the returned list.
     *
     * @param configurationResourceBuffer the resource to parse
     * @return a list of {@link KafkaConfiguration} objects
     */
    static List<KafkaConfiguration> parse(Buffer configurationResourceBuffer) {

        List<KafkaConfiguration> configurations = new ArrayList<>();
        JsonObject config = new JsonObject(configurationResourceBuffer);

        for (String topicPattern : config.fieldNames()) {
            try {
                Pattern pattern = Pattern.compile(topicPattern);
                final Map<String, String> additionalConfig = extractAdditionalConfig(config.getJsonObject(topicPattern));
                configurations.add(new KafkaConfiguration(pattern, additionalConfig));
                log.info("Topic '{}' successfully parsed and added to kafka configuration list", topicPattern);
            } catch (PatternSyntaxException patternException) {
                log.warn("Topic '{}' is not a valid regex pattern. Discarding this kafka configuration", topicPattern);
            }
        }

        return configurations;
    }

    private static Map<String, String> extractAdditionalConfig(JsonObject topicObject){
        Map<String, String> additionalConfigs = new HashMap<>();
        for (String configKey : topicObject.fieldNames()) {
            String configValue = topicObject.getString(configKey);
            additionalConfigs.put(configKey, configValue);
        }
        return additionalConfigs;
    }
}
