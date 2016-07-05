package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.RedisClient;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;

import java.util.List;
import java.util.Map;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerImpl implements QueueCircuitBreaker, RuleChangesObserver {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreakerImpl.class);

    private boolean active = true;
    private RuleProvider ruleProvider;
    private QueueCircuitBreakerStorage queueCircuitBreakerStorage;
    private QueueCircuitBreakerRulePatternToEndpointMapping ruleToEndpointMapping;

    public QueueCircuitBreakerImpl(Vertx vertx, RedisClient redisClient, String rulesPath, ResourceStorage storage, Map<String, Object> properties) {
        this.ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        this.ruleProvider.registerObserver(this);

        this.queueCircuitBreakerStorage = new RedisQueueCircuitBreakerStorage(redisClient);
        this.ruleToEndpointMapping = new QueueCircuitBreakerRulePatternToEndpointMapping();
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
        this.queueCircuitBreakerStorage.getQueueCircuitState(patternAndEndpointHash).setHandler(event -> {
            if(event.failed()){
                future.fail(event.cause());
            } else {
                future.complete(event.result());
            }
        });
        return future;
    }
}
