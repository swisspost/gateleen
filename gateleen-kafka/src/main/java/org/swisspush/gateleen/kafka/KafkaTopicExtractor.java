package org.swisspush.gateleen.kafka;

import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.StringUtils;

import java.util.Optional;

/**
 * Extracts the topic name from a request uri.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class KafkaTopicExtractor {

    private final String streamingPath;

    KafkaTopicExtractor(String streamingPath) {
        this.streamingPath = streamingPath;
    }

    /**
     * Extract the topic from the provided request by removing the configured streaming path. When no topic (or an
     * empty string) can be extracted, an {@link Optional#empty()} will be returned.
     *
     * @param request the request to extract the topic from the uri
     * @return an {@link Optional} holding the extracted topic
     */
    Optional<String> extractTopic(HttpServerRequest request) {
        final Logger requestLog = RequestLoggerFactory.getLogger(KafkaTopicExtractor.class, request);
        String topic = org.apache.commons.lang3.StringUtils.removeStart(request.uri(), streamingPath);
        if (StringUtils.isNotEmptyTrimmed(topic)) {
            return Optional.of(topic);
        }
        requestLog.warn("Extracted an empty string as topic from uri {}", request.uri());
        return Optional.empty();
    }
}
