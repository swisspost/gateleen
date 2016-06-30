package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private boolean active;
    private RuleProvider ruleProvider;
    private QueueCircuitBreakerStorage queueCircuitBreakerStorage;

    public QueueCircuitBreakerImpl(Vertx vertx, RedisClient redisClient, String rulesPath, ResourceStorage storage, Map<String, Object> properties) {
        this.ruleProvider = new RuleProvider(vertx, rulesPath, storage, properties);
        this.ruleProvider.registerObserver(this);

        this.queueCircuitBreakerStorage = new RedisQueueCircuitBreakerStorage(redisClient);
    }

    @Override
    public void rulesChanged(List<Rule> rules) {

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
        return future;
    }
}
