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
    private QueueCircuitBreakerRulePatternToCircuitMapping ruleToCircuitMapping;

    private int errorThresholdPercentage;
    private long entriesMaxAgeMS;
    private long minSampleCount;
    private long maxSampleCount;

    private String redisquesAddress;

    public QueueCircuitBreakerImpl(Vertx vertx, QueueCircuitBreakerStorage queueCircuitBreakerStorage, RuleProvider ruleProvider, QueueCircuitBreakerRulePatternToCircuitMapping ruleToCircuitMapping) {
        this.vertx = vertx;
        this.redisquesAddress = Address.redisquesAddress();
        this.queueCircuitBreakerStorage = queueCircuitBreakerStorage;
        this.ruleProvider = ruleProvider;
        this.ruleProvider.registerObserver(this);
        this.ruleToCircuitMapping = ruleToCircuitMapping;

        this.errorThresholdPercentage = 50;
        this.entriesMaxAgeMS = 1000 * 60 * 60; //1h
        this.minSampleCount = 3;
        this.maxSampleCount = 10;
    }

    @Override
    public void rulesChanged(List<Rule> rules) {
        log.info("rules have changed, renew rule to circuit mapping");
        this.ruleToCircuitMapping.updateRulePatternToCircuitMapping(rules);
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
        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if(patternAndCircuitHash != null){
            this.queueCircuitBreakerStorage.getQueueCircuitState(patternAndCircuitHash).setHandler(event -> {
                if(event.failed()){
                    future.fail(event.cause());
                } else {
                    future.complete(event.result());
                    if(QueueCircuitState.OPEN == event.result()){
                        lockQueueSync(queueName, queuedRequest);
                    }
                }
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(future, queueName, queuedRequest);
        }
        return future;
    }

    @Override
    public Future<Void> updateStatistics(String queueName, HttpRequest queuedRequest, QueueResponseType queueResponseType) {
        Future<Void> future = Future.future();
        String requestId = getRequestUniqueId(queuedRequest);
        long currentTS = System.currentTimeMillis();

        // TODO BEGIN REMOVE
        QueueResponseType type;
        if(queueName.contains("fail")){
            type = QueueResponseType.FAILURE;
        } else {
            type = queueResponseType;
        }
        // TODO END REMOVE

        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if(patternAndCircuitHash != null) {
            this.queueCircuitBreakerStorage.updateStatistics(patternAndCircuitHash, requestId, currentTS,
                    errorThresholdPercentage, entriesMaxAgeMS, minSampleCount,
                    maxSampleCount, type).setHandler(event -> {
                if (event.failed()) {
                    future.fail(event.cause());
                } else {
                    if(UpdateStatisticsResult.OPENED == event.result()) {
                        lockQueueSync(queueName, queuedRequest);
                    }
                    future.complete();
                }
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(future, queueName, queuedRequest);
        }
        return future;
    }

    @Override
    public Future<Void> closeCircuit(HttpRequest queuedRequest) {
        Future<Void> future = Future.future();
        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if(patternAndCircuitHash != null){
            queueCircuitBreakerStorage.closeCircuit(patternAndCircuitHash).setHandler(event -> {
                if(event.failed()){
                    future.fail(event.cause());
                    return;
                }
                future.complete();
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(future, null, queuedRequest);
        }
        return future;
    }

    @Override
    public Future<Void> closeAllCircuits() {
        return queueCircuitBreakerStorage.closeAllCircuits();
    }

    @Override
    public Future<Void> reOpenCircuit(HttpRequest queuedRequest) {
        Future<Void> future = Future.future();
        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if(patternAndCircuitHash != null){
            queueCircuitBreakerStorage.reOpenCircuit(patternAndCircuitHash).setHandler(event -> {
                if(event.failed()){
                    future.fail(event.cause());
                    return;
                }
                future.complete();
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(future, null, queuedRequest);
        }
        return future;
    }

    @Override
    public Future<Void> lockQueue(String queueName, HttpRequest queuedRequest) {
        Future<Void> future = Future.future();

        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if(patternAndCircuitHash != null){
            queueCircuitBreakerStorage.lockQueue(queueName, patternAndCircuitHash).setHandler(event -> {
                if(event.failed()){
                    future.fail(event.cause());
                    return;
                }
                vertx.eventBus().send(redisquesAddress, buildPutLockOperation(queueName, "queue_circuit_breaker"), new Handler<AsyncResult<Message<JsonObject>>>() {
                    @Override
                    public void handle(AsyncResult<Message<JsonObject>> reply) {
                        if(reply.failed()){
                            future.fail(reply.cause());
                            return;
                        }
                        if (OK.equals(reply.result().body().getString(STATUS))) {
                            log.info("locked queue '" + queueName + "' because the circuit has been opened");
                            future.complete();
                        } else {
                            future.fail("failed to lock queue '" + queueName + "'. Queue should have been locked, because the circuit has been opened");
                        }
                    }
                });
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(future, queueName, queuedRequest);
        }
        return future;
    }

    private void lockQueueSync(String queueName, HttpRequest queuedRequest){
        lockQueue(queueName, queuedRequest).setHandler(event -> {
            if(event.failed()){
                log.warn(event.cause().getMessage());
            }
        });
    }

    private void failWithNoRuleToCircuitMappingMessage(Future future, String queueName, HttpRequest request){
        if(queueName == null){
            future.fail("no rule to circuit mapping found for uri " + request.getUri());
        } else {
            future.fail("no rule to circuit mapping found for queue '" + queueName + "' and uri " + request.getUri());
        }
    }

    private PatternAndCircuitHash getPatternAndCircuitHashFromRequest(HttpRequest request){
        return this.ruleToCircuitMapping.getCircuitFromRequestUri(request.getUri());
    }

    private String getRequestUniqueId(HttpRequest request){
        String unique = request.getHeaders().get("x-rp-unique_id");
        if (unique == null) {
            unique = request.getHeaders().get("x-rp-unique-id");
        }
        return unique;
    }
}
