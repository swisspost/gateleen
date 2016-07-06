package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;

import java.util.List;

import static org.swisspush.redisques.util.RedisquesAPI.OK;
import static org.swisspush.redisques.util.RedisquesAPI.STATUS;
import static org.swisspush.redisques.util.RedisquesAPI.buildPutLockOperation;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerImpl implements QueueCircuitBreaker, RuleChangesObserver {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreakerImpl.class);

    private Vertx vertx;
    private boolean circuitCheckEnabled = true;
    private boolean statisticsUpdateEnabled = true;
    private RuleProvider ruleProvider;
    private QueueCircuitBreakerStorage queueCircuitBreakerStorage;
    private QueueCircuitBreakerRulePatternToEndpointMapping ruleToEndpointMapping;

    private int errorThresholdPercentage;
    private long entriesMaxAgeMS;
    private long minSampleCount;
    private long maxSampleCount;

    private String redisquesAddress;

    public QueueCircuitBreakerImpl(Vertx vertx, QueueCircuitBreakerStorage queueCircuitBreakerStorage, RuleProvider ruleProvider, QueueCircuitBreakerRulePatternToEndpointMapping ruleToEndpointMapping) {
        this.vertx = vertx;
        this.redisquesAddress = Address.redisquesAddress();
        this.queueCircuitBreakerStorage = queueCircuitBreakerStorage;
        this.ruleProvider = ruleProvider;
        this.ruleProvider.registerObserver(this);
        this.ruleToEndpointMapping = ruleToEndpointMapping;

        this.errorThresholdPercentage = 50;
        this.entriesMaxAgeMS = 1000 * 60 * 60; //1h
        this.minSampleCount = 3;
        this.maxSampleCount = 10;
    }

    @Override
    public void rulesChanged(List<Rule> rules) {
        log.info("rules have changed, renew rule to endpoint mapping");
        this.ruleToEndpointMapping.updateRulePatternToEndpointMapping(rules);
    }

    @Override
    public void enableCircuitCheck(boolean circuitCheckEnabled) {
        this.circuitCheckEnabled = circuitCheckEnabled;
    }

    @Override
    public boolean isCircuitCheckEnabled() { return circuitCheckEnabled; }

    @Override
    public void enableStatisticsUpdate(boolean statisticsUpdateEnabled) { this.statisticsUpdateEnabled = statisticsUpdateEnabled; }

    @Override
    public boolean isStatisticsUpdateEnabled() { return statisticsUpdateEnabled; }

    @Override
    public Future<QueueCircuitState> handleQueuedRequest(String queueName, HttpRequest queuedRequest){
        Future<QueueCircuitState> future = Future.future();
        PatternAndEndpointHash patternAndEndpointHash = getPatternAndEndpointHashFromRequest(queuedRequest);
        if(patternAndEndpointHash != null){
            this.queueCircuitBreakerStorage.getQueueCircuitState(patternAndEndpointHash).setHandler(event -> {
                if(event.failed()){
                    future.fail(event.cause());
                } else {
                    future.complete(event.result());
                }
            });
        } else {
            failWithNoRuleToEndpointMappingMessage(future, queueName, queuedRequest);
        }
        return future;
    }

    @Override
    public Future<Void> updateStatistics(String queueName, HttpRequest queuedRequest, QueueResponseType queueResponseType) {
        Future<Void> future = Future.future();
        String requestId = getRequestUniqueId(queuedRequest);
        long currentTS = System.currentTimeMillis();

        QueueResponseType type;
        if(queueName.contains("fail")){
            type = QueueResponseType.FAILURE;
        } else {
            type = QueueResponseType.SUCCESS;
        }

        PatternAndEndpointHash patternAndEndpointHash = getPatternAndEndpointHashFromRequest(queuedRequest);
        if(patternAndEndpointHash != null) {
            this.queueCircuitBreakerStorage.updateStatistics(patternAndEndpointHash, requestId, currentTS,
                    errorThresholdPercentage, entriesMaxAgeMS, minSampleCount,
                    maxSampleCount, type).setHandler(event -> {
                if (event.failed()) {
                    future.fail(event.cause());
                } else {
                    lockQueueIfNeeded(queueName, event.result());
                    future.complete();
                }
            });
        } else {
            failWithNoRuleToEndpointMappingMessage(future, queueName, queuedRequest);
        }
        return future;
    }

    private void lockQueueIfNeeded(String queueName, UpdateStatisticsResult result){
        if(UpdateStatisticsResult.OPENED == result){
            vertx.eventBus().send(redisquesAddress, buildPutLockOperation(queueName, "queue_circuit_breaker"), new Handler<AsyncResult<Message<JsonObject>>>() {
                @Override
                public void handle(AsyncResult<Message<JsonObject>> reply) {
                    if (OK.equals(reply.result().body().getString(STATUS))) {
                        log.info("locked queue '" + queueName + "' because the circuit has been opened");
                    } else {
                        log.warn("failed to lock queue '" + queueName + "'. Queue should have been locked, because the circuit has been opened");
                    }
                }
            });
        }
    }

    private void failWithNoRuleToEndpointMappingMessage(Future future, String queueName, HttpRequest request){
        future.fail("no rule to endpoint mapping found for queue '" + queueName + "' and uri " + request.getUri());
    }

    private PatternAndEndpointHash getPatternAndEndpointHashFromRequest(HttpRequest request){
        return this.ruleToEndpointMapping.getEndpointFromRequestUri(request.getUri());
    }

    private String getRequestUniqueId(HttpRequest request){
        String unique = request.getHeaders().get("x-rp-unique_id");
        if (unique == null) {
            unique = request.getHeaders().get("x-rp-unique-id");
        }
        return unique;
    }
}
