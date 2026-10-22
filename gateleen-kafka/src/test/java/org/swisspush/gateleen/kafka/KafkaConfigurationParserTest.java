package org.swisspush.gateleen.kafka;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tests for the {@link KafkaConfigurationParser} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaConfigurationParserTest {

    private final String CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_valid_kafka_topic_configuration", true);
    private final String INVALID_PATTERN_CONFIG_RESOURCE = ResourcesUtils.loadResource("testresource_invalid_pattern_kafka_topic_configuration", true);
    private final String CONFIG_WILDCARD_RESOURCE = ResourcesUtils.loadResource("testresource_wildcard_kafka_topic_configuration", true);

    @Test
    public void parseAllValid(TestContext context) {
        List<KafkaConfiguration> configurations = KafkaConfigurationParser.parse(Buffer.buffer(CONFIG_RESOURCE), new HashMap<>());
        context.assertEquals(2, configurations.size());

        // Note that the order of the parsed configurations matters!
        KafkaConfiguration config_1 = configurations.get(0);
        context.assertEquals(Pattern.compile("my.topic.*").pattern(), config_1.getTopic().pattern());
        Map<String, String> expected_1 = new HashMap<>() {{
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("bootstrap.servers", "localhost:9092");
        }};
        context.assertEquals(expected_1, config_1.getConfigurations());

        KafkaConfiguration config_2 = configurations.get(1);
        context.assertEquals(Pattern.compile(".").pattern(), config_2.getTopic().pattern());
        Map<String, String> expected_2 = new HashMap<>() {{
            put("bootstrap.servers", "localhost:9093");
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("acks", "1");
        }};
        context.assertEquals(expected_2, config_2.getConfigurations());
    }

    @Test
    public void parseRejectsConfigurationWhenValidPatternPrecedesInvalidPattern(TestContext context) {
        assertRejected(context, INVALID_PATTERN_CONFIG_RESOURCE, "*");
    }

    @Test
    public void parseRejectsConfigurationWhenInvalidPatternPrecedesValidAndCatchAll(TestContext context) {
        String configuration = "{\"[\":{},\"valid.topic\":{},\".+\":{}}";
        assertRejected(context, configuration, "[");
    }

    @Test
    public void parseWildcardValid(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("kafka.host", "localhost");
        properties.put("kafka.port", "9095");
        List<KafkaConfiguration> configurations = KafkaConfigurationParser.parse(Buffer.buffer(CONFIG_WILDCARD_RESOURCE), properties);
        context.assertEquals(1, configurations.size());

        // Note that the order of the parsed configurations matters!
        KafkaConfiguration config = configurations.get(0);
        context.assertEquals(Pattern.compile("my.properties.topic.*").pattern(), config.getTopic().pattern());
        Map<String, String> expected_1 = new HashMap<>() {{
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            put("acks", "all");
            put("bootstrap.servers", "localhost:9095");
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        }};
        context.assertEquals(expected_1, config.getConfigurations());
    }

    @Test
    public void parseWildcardNoEnvProps(TestContext context) {
        IllegalArgumentException exception = assertParsingFails(context, Buffer.buffer(CONFIG_WILDCARD_RESOURCE), "placeholders");
        context.assertNotNull(exception.getCause());
    }

    @Test
    public void parseWithNullPropertiesAcceptsPlainConfiguration(TestContext context) {
        List<KafkaConfiguration> configurations = KafkaConfigurationParser.parse(Buffer.buffer(CONFIG_RESOURCE), null);
        context.assertEquals(2, configurations.size());
    }

    @Test
    public void parseWithNullPropertiesRejectsUnresolvedPlaceholder(TestContext context) {
        String configuration = "{\"topic\":{\"bootstrap.servers\":\"${kafka.host}:9092\"}}";
        IllegalArgumentException exception = assertParsingFails(context, Buffer.buffer(configuration), "placeholders", null);
        context.assertNotNull(exception.getCause());
    }

    @Test
    public void parseClientIdsAreOptionalAndUniquelySuffixed(TestContext context) {
        String configuration = "{\"plain\":{},\"empty\":{\"client.id\":\"\"},"
                + "\"first\":{\"client.id\":\"client\"},\"second\":{\"client.id\":\"client\"}}";
        List<KafkaConfiguration> firstParse = KafkaConfigurationParser.parse(Buffer.buffer(configuration), new HashMap<>());
        List<KafkaConfiguration> secondParse = KafkaConfigurationParser.parse(Buffer.buffer(configuration), new HashMap<>());

        context.assertEquals(4, firstParse.size());
        context.assertEquals(4, secondParse.size());
        context.assertFalse(firstParse.get(0).getConfigurations().containsKey("client.id"));
        context.assertEquals("", firstParse.get(1).getConfigurations().get("client.id"));

        Set<String> clientIds = new HashSet<>();
        for (List<KafkaConfiguration> configurations : List.of(firstParse, secondParse)) {
            for (int i = 2; i < configurations.size(); i++) {
                String clientId = configurations.get(i).getConfigurations().get("client.id");
                context.assertTrue(clientId.matches("client-\\d+"));
                clientIds.add(clientId);
            }
        }
        context.assertEquals(4, clientIds.size());
    }

    @Test
    public void parseWildcardTopicKeyPreservesOrderAndPattern(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("topic.prefix", "orders");
        String configuration = "{\"${topic.prefix}.*\":{},\".+\":{}}";

        List<KafkaConfiguration> configurations = KafkaConfigurationParser.parse(Buffer.buffer(configuration), properties);

        context.assertEquals(2, configurations.size());
        context.assertEquals("orders.*", configurations.get(0).getTopic().pattern());
        context.assertEquals(".+", configurations.get(1).getTopic().pattern());
        context.assertTrue(configurations.get(0).getTopic().matcher("orders-123").matches());
        context.assertTrue(configurations.get(1).getTopic().matcher("other").matches());
    }

    @Test
    public void parseWildcardProducingInvalidTopicPatternRejectsConfiguration(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("pattern", "*");
        IllegalArgumentException exception = assertParsingFails(context,
                Buffer.buffer("{\"${pattern}\":{}}"), "Invalid topic regex pattern '*'", properties);

        context.assertTrue(exception.getCause() instanceof PatternSyntaxException);
        context.assertEquals("*", ((PatternSyntaxException) exception.getCause()).getPattern());
    }

    @Test
    public void parseEscapedPatternAfterWildcardReplacement(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("topic.prefix", "orders");
        properties.put("kafka.host", "localhost");
        String configuration = "{\"${topic.prefix}\\\\\\\\.v1\":{\"bootstrap.servers\":\"${kafka.host}:9092\"}}";

        List<KafkaConfiguration> configurations = KafkaConfigurationParser.parse(Buffer.buffer(configuration), properties);

        context.assertEquals(1, configurations.size());
        Pattern pattern = configurations.get(0).getTopic();
        context.assertEquals("orders\\.v1", pattern.pattern());
        context.assertTrue(pattern.matcher("orders.v1").matches());
        context.assertFalse(pattern.matcher("ordersXv1").matches());
    }

    @Test
    public void parseEscapedBackslashPatternAfterWildcardReplacement(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("topic.prefix", "orders");
        properties.put("kafka.host", "localhost");
        String jsonEscapedBackslash = "\\\\";
        String patternKey = "${topic.prefix}" + jsonEscapedBackslash.repeat(4) + "item";
        String configuration = "{\"" + patternKey + "\":{\"bootstrap.servers\":\"${kafka.host}:9092\"}}";

        List<KafkaConfiguration> configurations = KafkaConfigurationParser.parse(Buffer.buffer(configuration), properties);

        context.assertEquals(1, configurations.size());
        Pattern pattern = configurations.get(0).getTopic();
        context.assertEquals("orders\\\\item", pattern.pattern());
        context.assertTrue(pattern.matcher("orders\\item").matches());
        context.assertFalse(pattern.matcher("ordersXitem").matches());
        context.assertFalse(pattern.matcher("orders\\\\item").matches());
    }

    @Test
    public void parseEmptyConfigurationIsValid(TestContext context) {
        context.assertTrue(KafkaConfigurationParser.parse(Buffer.buffer("{}"), new HashMap<>()).isEmpty());
    }

    @Test
    public void parseMalformedJsonAndNonObjectRootsRejectConfiguration(TestContext context) {
        for (String configuration : List.of("", "{\"unterminated\"", "[]", "\"root\"", "null")) {
            IllegalArgumentException exception = assertParsingFails(context, Buffer.buffer(configuration), "rejecting entire Kafka configuration");
            context.assertNotNull(exception.getCause());
        }
    }

    @Test
    public void parseUnexpectedEntryShapeOrTypeRejectsConfiguration(TestContext context) {
        IllegalArgumentException shapeException = assertParsingFails(context, Buffer.buffer("{\"topic\":[]}"), "JSON object");
        context.assertFalse(shapeException.getMessage().isEmpty());

        IllegalArgumentException typeException = assertParsingFails(context,
                Buffer.buffer("{\"topic\":{\"client.id\":123}}"), "client.id");
        context.assertFalse(typeException.getMessage().isEmpty());
    }

    @Test
    public void parsePartiallyUnresolvedWildcardRejectsConfiguration(TestContext context) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("kafka.host", "localhost");
        String configuration = "{\"topic\":{\"bootstrap.servers\":\"${kafka.host}:${kafka.port}\"}}";

        IllegalArgumentException exception = assertParsingFails(context, Buffer.buffer(configuration), "placeholders", properties);
        context.assertNotNull(exception.getCause());
    }

    private void assertRejected(TestContext context, String configuration, String invalidPattern) {
        IllegalArgumentException exception = assertParsingFails(context, Buffer.buffer(configuration), invalidPattern);
        context.assertTrue(exception.getCause() instanceof PatternSyntaxException);
        context.assertEquals(invalidPattern, ((PatternSyntaxException) exception.getCause()).getPattern());
    }

    private IllegalArgumentException assertParsingFails(TestContext context, Buffer configuration, String messageFragment) {
        return assertParsingFails(context, configuration, messageFragment, new HashMap<>());
    }

    private IllegalArgumentException assertParsingFails(TestContext context, Buffer configuration, String messageFragment,
                                                        Map<String, Object> properties) {
        try {
            KafkaConfigurationParser.parse(configuration, properties);
            context.fail("Expected parser to reject the entire configuration");
            return null;
        } catch (IllegalArgumentException exception) {
            context.assertTrue(exception.getMessage().contains(messageFragment));
            context.assertTrue(exception.getMessage().contains("rejecting entire Kafka configuration"));
            return exception;
        }
    }
}
