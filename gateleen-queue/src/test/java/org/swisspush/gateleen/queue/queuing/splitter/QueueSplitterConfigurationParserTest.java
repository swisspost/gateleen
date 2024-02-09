package org.swisspush.gateleen.queue.queuing.splitter;

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
 * Tests for {@link QueueSplitterConfigurationParser} class
 *
 * @author https://github.com/gcastaldi [Giannandrea Castaldi]
 */
@RunWith(VertxUnitRunner.class)
public class QueueSplitterConfigurationParserTest {

    private final String CONFIGURATION_VALID = ResourcesUtils.loadResource(
            "testresource_queuesplitter_configuration_valid_1",
            true
    );

    private final String CONFIGURATION_INVALID = ResourcesUtils.loadResource(
            "testresource_queuesplitter_configuration_missing_postfix",
            true
    );

    private final String CONFIGURATION_INVALID_JSON = ResourcesUtils.loadResource(
            "testresource_queuesplitter_configuration_invalid_json",
            true
    );

    private final String CONFIGURATION_WITH_PROPS = ResourcesUtils.loadResource(
            "testresource_queuesplitter_configuration_with_props",
            true
    );

    @Test
    public void parseWithAllValid(TestContext context) {

        // Given
        Buffer configurationResourceBuffer = Buffer.buffer(CONFIGURATION_VALID);
        HashMap<String, Object> properties = new HashMap<>();

        // When
        List<QueueSplitterConfiguration> configurations = QueueSplitterConfigurationParser.parse(
                configurationResourceBuffer,
                properties
        );

        // Then
        context.assertEquals(3, configurations.size());

        // Note that the order of the parsed configurations matters!
        QueueSplitterConfiguration config_1 = configurations.get(0);
        context.assertEquals(Pattern.compile("my-queue-1").pattern(), config_1.getQueue().pattern());
        context.assertEquals("-", config_1.getPostfixDelimiter());
        context.assertEquals(List.of("A", "B", "C", "D"), config_1.getPostfixFromStatic());
        context.assertNull(config_1.getPostfixFromHeader());
        context.assertNull(config_1.getPostfixFromUrl());
        context.assertTrue(config_1.isSplitStatic());
        context.assertFalse(config_1.isSplitFromRequest());

        QueueSplitterConfiguration config_2 = configurations.get(1);
        context.assertEquals(Pattern.compile("my-queue-[0-9]+").pattern(), config_2.getQueue().pattern());
        context.assertEquals("+", config_2.getPostfixDelimiter());
        context.assertNull(config_2.getPostfixFromStatic());
        context.assertEquals("x-rp-deviceid", config_2.getPostfixFromHeader());
        context.assertNull(config_2.getPostfixFromUrl());
        context.assertFalse(config_2.isSplitStatic());
        context.assertTrue(config_2.isSplitFromRequest());

        QueueSplitterConfiguration config_3 = configurations.get(2);
        context.assertEquals(Pattern.compile("my-queue-[a-zA-Z]+").pattern(), config_3.getQueue().pattern());
        context.assertEquals("_", config_3.getPostfixDelimiter());
        context.assertNull(config_3.getPostfixFromStatic());
        context.assertNull(config_3.getPostfixFromHeader());
        context.assertEquals(
                Pattern.compile(".*/path1/(.*)/path3/path4/.*").pattern(),
                config_3.getPostfixFromUrl().pattern());
        context.assertFalse(config_3.isSplitStatic());
        context.assertTrue(config_3.isSplitFromRequest());
    }

    @Test
    public void parseWithOneValidOneNot(TestContext context) {

        // Given
        Buffer configurationResourceBuffer = Buffer.buffer(CONFIGURATION_INVALID);
        HashMap<String, Object> properties = new HashMap<>();

        // When
        List<QueueSplitterConfiguration> configurations = QueueSplitterConfigurationParser.parse(
                configurationResourceBuffer,
                properties
        );

        // Then
        context.assertEquals(1, configurations.size());

        QueueSplitterConfiguration config_1 = configurations.get(0);
        context.assertEquals(Pattern.compile("my-queue-1").pattern(), config_1.getQueue().pattern());
        context.assertEquals("-", config_1.getPostfixDelimiter());
        context.assertEquals(List.of("A", "B", "C", "D"), config_1.getPostfixFromStatic());
        context.assertNull(config_1.getPostfixFromHeader());
        context.assertNull(config_1.getPostfixFromUrl());
    }
    @Test
    public void parseWithInvalidJson(TestContext context) {

        // Given
        Buffer configurationResourceBuffer = Buffer.buffer(CONFIGURATION_INVALID_JSON);
        HashMap<String, Object> properties = new HashMap<>();

        // When
        List<QueueSplitterConfiguration> configurations = QueueSplitterConfigurationParser.parse(
                configurationResourceBuffer,
                properties
        );

        // Then
        context.assertEquals(0, configurations.size());
    }

    @Test
    public void parseWithValidAndProps(TestContext context) {

        // Given
        Buffer configurationResourceBuffer = Buffer.buffer(CONFIGURATION_WITH_PROPS);
        Map<String, Object> properties = Map.of("queue.splitter.delimiter", "_");

        // When
        List<QueueSplitterConfiguration> configurations = QueueSplitterConfigurationParser.parse(
                configurationResourceBuffer,
                properties
        );

        // Then
        context.assertEquals(1, configurations.size());

        QueueSplitterConfiguration config_1 = configurations.get(0);
        context.assertEquals(Pattern.compile("my-queue-[0-9]+").pattern(), config_1.getQueue().pattern());
        context.assertEquals("_", config_1.getPostfixDelimiter());
        context.assertNull(config_1.getPostfixFromStatic());
        context.assertEquals("x-rp-deviceid", config_1.getPostfixFromHeader());
        context.assertNull(config_1.getPostfixFromUrl());
    }
    @Test
    public void parseWithValidAndMissingProps(TestContext context) {

        // Given
        Buffer configurationResourceBuffer = Buffer.buffer(CONFIGURATION_WITH_PROPS);
        Map<String, Object> properties = new HashMap<>();

        // When
        List<QueueSplitterConfiguration> configurations = QueueSplitterConfigurationParser.parse(
                configurationResourceBuffer,
                properties
        );

        // Then
        context.assertEquals(0, configurations.size());
    }
}
