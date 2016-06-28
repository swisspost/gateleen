package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;

import java.util.List;
import java.util.Map;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreaker implements RuleChangesObserver {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreaker.class);

    private ResourceStorage storage;
    private RuleProvider ruleProvider;

    public QueueCircuitBreaker(Vertx vertx, String rulesPath, ResourceStorage storage, Map<String, Object> properties) {
        this.storage = storage;
        this.ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        this.ruleProvider.registerObserver(this);
    }

    @Override
    public void rulesChanged(List<Rule> rules) {

    }
}
