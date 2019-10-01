package org.swisspush.gateleen.kafka;

import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.StringUtils;

import java.util.Optional;

public class KafkaTopicExtractor {

    private final String streamingPath;

    public KafkaTopicExtractor(String streamingPath) {
        this.streamingPath = streamingPath;
    }

    public Optional<String> extractTopic(HttpServerRequest request) {
        final Logger requestLog = RequestLoggerFactory.getLogger(KafkaTopicExtractor.class, request);
        String topic = org.apache.commons.lang3.StringUtils.removeStart(request.uri(), streamingPath);
        if (StringUtils.isNotEmptyTrimmed(topic)) {
            return Optional.of(topic);
        }
        requestLog.warn("Extracted an empty string as topic from uri " + request.uri());
        return Optional.empty();
    }
}
