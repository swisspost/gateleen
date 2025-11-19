package org.swisspush.gateleen.logging;

import io.vertx.core.Vertx;
import org.apache.logging.log4j.core.Appender;
import org.swisspush.gateleen.core.event.TrackableEventPublish;

import java.util.HashMap;
import java.util.Map;

import static org.swisspush.gateleen.logging.LoggingResourceManager.UPDATE_ADDRESS;

/**
 * Default implementation of the {@link LogAppenderRepository} caching the {@link Appender} instances in a {@link Map}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DefaultLogAppenderRepository implements LogAppenderRepository {

    private final TrackableEventPublish trackableEventPublish;
    private Map<String, Appender> appenderMap = new HashMap<>();

    public DefaultLogAppenderRepository(Vertx vertx) {
        this.trackableEventPublish = new TrackableEventPublish(vertx);
        trackableEventPublish.consumer(vertx, UPDATE_ADDRESS, event -> clearRepository());
    }

    @Override
    public boolean hasAppender(String name) {
        return appenderMap.containsKey(name);
    }

    @Override
    public void addAppender(String name, Appender appender) {
        appenderMap.put(name, appender);
    }

    @Override
    public Appender getAppender(String name) {
        return appenderMap.get(name);
    }

    @Override
    public void clearRepository() {
        appenderMap.clear();
    }
}
