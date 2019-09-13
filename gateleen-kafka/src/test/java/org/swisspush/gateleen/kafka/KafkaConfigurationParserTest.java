package org.swisspush.gateleen.kafka;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tests for the {@link KafkaConfigurationParser} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaConfigurationParserTest {

    private final String CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_valid_kafka_topic_configuration", true);
    private final String INVALID_PATTERN_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_invalid_pattern_kafka_topic_configuration", true);

    @Test
    public void parseAllValid(TestContext context) {
        List<KafkaConfiguration> configurations = KafkaConfigurationParser.parse(Buffer.buffer(CONFIG_RESOURCE));
        context.assertEquals(2, configurations.size());

        // Note that the order of the parsed configurations matters!
        KafkaConfiguration config_1 = configurations.get(0);
        context.assertEquals(Pattern.compile("my.topic.*").pattern(), config_1.getTopic().pattern());
        Map<String, String> expected_1 = new HashMap<String, String>() {{
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("bootstrap.servers", "localhost:9092");
        }};
        context.assertEquals(expected_1, config_1.getConfigurations());

        KafkaConfiguration config_2 = configurations.get(1);
        context.assertEquals(Pattern.compile(".").pattern(), config_2.getTopic().pattern());
        Map<String, String> expected_2 = new HashMap<String, String>() {{
            put("bootstrap.servers", "localhost:9093");
        }};
        context.assertEquals(expected_2, config_2.getConfigurations());
    }

    @Test
    public void parseWithInvalidPattern(TestContext context) {
        List<KafkaConfiguration> configurations = KafkaConfigurationParser.parse(Buffer.buffer(INVALID_PATTERN_CONFIG_RESOURCE));
        context.assertEquals(1, configurations.size());

        // Note that the order of the parsed configurations matters!
        KafkaConfiguration config_1 = configurations.get(0);
        context.assertEquals(Pattern.compile("my.topic.*").pattern(), config_1.getTopic().pattern());
        Map<String, String> expected_1 = new HashMap<String, String>() {{
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("bootstrap.servers", "localhost:9092");
        }};
        context.assertEquals(expected_1, config_1.getConfigurations());
    }
}