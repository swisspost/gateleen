package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerImpl implements QueueCircuitBreaker, RuleChangesObserver {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreakerImpl.class);

    private boolean active = true;
    private RuleProvider ruleProvider;
    private QueueCircuitBreakerStorage queueCircuitBreakerStorage;
    private QueueCircuitBreakerRulePatternToEndpointMapping ruleToEndpointMapping;

    public QueueCircuitBreakerImpl(QueueCircuitBreakerStorage queueCircuitBreakerStorage, RuleProvider ruleProvider, QueueCircuitBreakerRulePatternToEndpointMapping ruleToEndpointMapping) {
        this.queueCircuitBreakerStorage = queueCircuitBreakerStorage;
        this.ruleProvider = ruleProvider;
        this.ruleProvider.registerObserver(this);
        this.ruleToEndpointMapping = ruleToEndpointMapping;
    }

    @Override
    public void rulesChanged(List<Rule> rules) {
        log.info("rules have changed, renew rule to endpoint mapping");
        this.ruleToEndpointMapping.updateRulePatternToEndpointMapping(rules);
    }

    @Override
    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public Future<QueueCircuitState> handleQueuedRequest(String queueName, HttpRequest queuedRequest){
        Future<QueueCircuitState> future = Future.future();
        PatternAndEndpointHash patternAndEndpointHash = this.ruleToEndpointMapping.getEndpointFromRequestUri(queuedRequest.getUri());
        if(patternAndEndpointHash != null){
            this.queueCircuitBreakerStorage.getQueueCircuitState(patternAndEndpointHash).setHandler(event -> {
                if(event.failed()){
                    future.fail(event.cause());
                } else {
                    future.complete(event.result());
                }
            });
        } else {
            future.fail("no rule to endpoint mapping found for queue '" + queueName + "' and uri " + queuedRequest.getUri());
        }
        return future;
    }
}
