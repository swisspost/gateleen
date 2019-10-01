package org.swisspush.gateleen.kafka;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.core.http.DummyHttpServerRequest;

import java.util.Optional;

/**
 * Test class for the {@link KafkaTopicExtractor}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class KafkaTopicExtractorTest {

    private KafkaTopicExtractor extractor;
    private final String PREFIX = "/playground/server/streaming/";

    @Before
    public void setUp() throws Exception {
        extractor = new KafkaTopicExtractor(PREFIX);
    }

    @Test
    public void emptyTopic(TestContext context) {
        TopicRequest request = new TopicRequest(PREFIX);
        context.assertFalse(extractor.extractTopic(request).isPresent());
    }

    @Test
    public void validTopic(TestContext context) {
        TopicRequest request = new TopicRequest(PREFIX + "my.topic.x");
        final Optional<String> optTopic = extractor.extractTopic(request);
        context.assertTrue(optTopic.isPresent());
        context.assertEquals("my.topic.x", optTopic.get());

        TopicRequest request2 = new TopicRequest(PREFIX + "abc/def/ghi/123");
        final Optional<String> optTopic2 = extractor.extractTopic(request2);
        context.assertTrue(optTopic2.isPresent());
        context.assertEquals("abc/def/ghi/123", optTopic2.get());
    }

    static class TopicRequest extends DummyHttpServerRequest {

        private final String uri;

        public TopicRequest(String uri) {
            this.uri = uri;
        }

        @Override
        public HttpMethod method() {
            return HttpMethod.POST;
        }

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public MultiMap headers() {
            return new CaseInsensitiveHeaders();
        }
    }
}
