package org.swisspush.gateleen.kafka;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StringUtils;

import java.util.ArrayList;
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
     * When the configuration resource contains invalid regex patterns, a warning will be logged and the corresponding
     * {@link KafkaConfiguration} object will not be included in the returned list.
     *
     * @param configurationResourceBuffer the resource to parse
     * @return a list of {@link KafkaConfiguration} objects
     */
    static List<KafkaConfiguration> parse(Buffer configurationResourceBuffer, Map<String, Object> properties) {

        String replacedConfig;
        JsonObject config;
        List<KafkaConfiguration> configurations = new ArrayList<>();

        try {
            replacedConfig = StringUtils.replaceWildcardConfigs(configurationResourceBuffer.toString(UTF_8), properties);
            config = new JsonObject(Buffer.buffer(replacedConfig));
        } catch (Exception e) {
            log.warn("Could not replace wildcards with environment properties for kafka configurations due to following reason: {}",
                    e.getMessage());
            return configurations;
        }

        for (String topicPattern : config.fieldNames()) {
            try {
                Pattern pattern = Pattern.compile(topicPattern);
                final Map<String, String> additionalConfig = extractAdditionalConfig(config.getJsonObject(topicPattern));
                setUniqueClientId(additionalConfig);
                configurations.add(new KafkaConfiguration(pattern, additionalConfig));
                log.info("Topic '{}' successfully parsed and added to kafka configuration list", topicPattern);
            } catch (PatternSyntaxException patternException) {
                log.warn("Topic '{}' is not a valid regex pattern. Discarding this kafka configuration", topicPattern);
            }
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

    private static Map<String, String> extractAdditionalConfig(JsonObject topicObject){
        Map<String, String> additionalConfigs = new HashMap<>();
        for (String configKey : topicObject.fieldNames()) {
            String configValue = topicObject.getString(configKey);
            additionalConfigs.put(configKey, configValue);
        }
        return additionalConfigs;
    }
}
