package org.swisspush.gateleen.queue.queuing.splitter;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Parses the splitter configuration resource in to a list of {@link QueueSplitterConfiguration}
 *
 * @author https://github.com/gcastaldi [Giannandrea Castaldi]
 */
public class QueueSplitterConfigurationParser {

    private static final Logger log = LoggerFactory.getLogger(QueueSplitterConfigurationParser.class);
    public static final String POSTFIX_FROM_STATIC_KEY = "postfixFromStatic";
    public static final String POSTFIX_FROM_REQUEST_KEY = "postfixFromRequest";
    public static final String POSTFIX_FROM_HEADER_KEY = "header";
    public static final String POSTFIX_FROM_URL_KEY = "url";
    public static final String POSTFIX_DELIMITER_KEY = "postfixDelimiter";
    public static final String DEFAULT_POSTFIX_DELIMITER = "-";

    static List<QueueSplitterConfiguration> parse(Buffer configurationResourceBuffer, Map<String, Object> properties) {

        JsonObject config;
        List<QueueSplitterConfiguration> queueSplitterConfigurations = new ArrayList<>();
        try {
            String resolvedConfiguration = StringUtils.replaceWildcardConfigs(
                    configurationResourceBuffer.toString(StandardCharsets.UTF_8),
                    properties
            );
            config = new JsonObject(Buffer.buffer(resolvedConfiguration));
        } catch (Exception ex) {
            log.warn("Could not replace wildcards with environment properties for queue splitter configurations or json invalid. Here the reason: {}",
                    ex.getMessage());
            return queueSplitterConfigurations;
        }

        for (String queuePattern : config.fieldNames()) {
            try {
                Pattern pattern = Pattern.compile(queuePattern);
                JsonObject queueConfig = config.getJsonObject(queuePattern);
                JsonArray postfixFromStatic = queueConfig.getJsonArray(POSTFIX_FROM_STATIC_KEY);
                if (postfixFromStatic != null) {
                    List<String> staticPostfixes = postfixFromStatic.stream().map(Object::toString).collect(Collectors.toList());
                    queueSplitterConfigurations.add(new QueueSplitterConfiguration(
                            pattern,
                            queueConfig.getString("postfixDelimiter", DEFAULT_POSTFIX_DELIMITER),
                            staticPostfixes,
                            null,
                            null
                    ));
                } else {
                    JsonObject postfixFromRequest = queueConfig.getJsonObject(POSTFIX_FROM_REQUEST_KEY);
                    String postfixFromHeader = postfixFromRequest != null ? postfixFromRequest.getString(POSTFIX_FROM_HEADER_KEY) : null;
                    String postfixFromUrl = postfixFromRequest != null ? postfixFromRequest.getString(POSTFIX_FROM_URL_KEY) : null;
                    if (postfixFromHeader != null || postfixFromUrl != null) {
                        queueSplitterConfigurations.add(new QueueSplitterConfiguration(
                                pattern,
                                queueConfig.getString(POSTFIX_DELIMITER_KEY, DEFAULT_POSTFIX_DELIMITER),
                                null,
                                postfixFromHeader,
                                postfixFromUrl != null ? Pattern.compile(postfixFromUrl) : null
                        ));
                    } else {
                        log.warn("Queue splitter configuration without a postfix definition");
                    }
                }
            } catch (PatternSyntaxException patternException) {
                log.warn("Queue splitter '{}' is not a valid regex pattern. Discarding this queue splitter configuration", queuePattern);
            }
        }

        return queueSplitterConfigurations;
    }

}
