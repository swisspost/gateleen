package org.swisspush.gateleen.logging;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import org.apache.logging.log4j.core.Appender;

import java.util.HashMap;
import java.util.Map;

import static org.swisspush.gateleen.logging.LoggingResourceManager.UPDATE_ADDRESS;

/**
 * Default implementation of the {@link LogAppenderRepository} caching the {@link Appender} instances in a {@link Map}
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class DefaultLogAppenderRepository implements LogAppenderRepository {

    private Map<String, Appender> appenderMap = new HashMap<>();

    public DefaultLogAppenderRepository(Vertx vertx) {
        vertx.eventBus().consumer(UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> clearRepository());
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
