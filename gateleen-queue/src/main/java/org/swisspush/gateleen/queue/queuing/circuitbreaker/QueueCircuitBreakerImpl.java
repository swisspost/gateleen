package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.*;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.swisspush.redisques.util.RedisquesAPI.*;


/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerImpl implements QueueCircuitBreaker, RuleChangesObserver, Refreshable {

    private Logger log = LoggerFactory.getLogger(QueueCircuitBreakerImpl.class);

    private Vertx vertx;
    private QueueCircuitBreakerStorage queueCircuitBreakerStorage;
    private QueueCircuitBreakerRulePatternToCircuitMapping ruleToCircuitMapping;
    private QueueCircuitBreakerConfigurationResourceManager configResourceManager;

    private String redisquesAddress;

    private long openToHalfOpenTimerId = -1;
    private long unlockQueuesTimerId = -1;
    private long unlockSampleQueuesTimerId = -1;

    public QueueCircuitBreakerImpl(Vertx vertx, QueueCircuitBreakerStorage queueCircuitBreakerStorage, RuleProvider ruleProvider, QueueCircuitBreakerRulePatternToCircuitMapping ruleToCircuitMapping, QueueCircuitBreakerConfigurationResourceManager configResourceManager, Handler<HttpServerRequest> queueCircuitBreakerHttpRequestHandler, int requestHandlerPort) {
        this.vertx = vertx;
        this.redisquesAddress = Address.redisquesAddress();
        this.queueCircuitBreakerStorage = queueCircuitBreakerStorage;
        ruleProvider.registerObserver(this);
        this.ruleToCircuitMapping = ruleToCircuitMapping;
        this.configResourceManager = configResourceManager;

        this.configResourceManager.addRefreshable(this);

        registerPeriodicTasks();

        // in Vert.x 2x 100-continues was activated per default, in vert.x 3x it is off per default.
        HttpServerOptions options = new HttpServerOptions().setHandle100ContinueAutomatically(true);

        vertx.createHttpServer(options).requestHandler(queueCircuitBreakerHttpRequestHandler).listen(requestHandlerPort, event -> {
            if(event.succeeded()){
                log.info("Successfully listening to port " + requestHandlerPort);
            } else {
                log.error("Unable to listen to port " + requestHandlerPort + ". Cannot handle QueueCircuitBreaker http requests");
            }
        });
    }

    private void registerPeriodicTasks(){
        registerOpenToHalfOpenTask();
        registerUnlockQueuesTask();
        registerUnlockSampleQueuesTask();
    }

    private void registerOpenToHalfOpenTask(){
        boolean openToHalfOpenTaskEnabled = getConfig().isOpenToHalfOpenTaskEnabled();
        vertx.cancelTimer(openToHalfOpenTimerId);
        if(openToHalfOpenTaskEnabled){
            openToHalfOpenTimerId = vertx.setPeriodic(getConfig().getOpenToHalfOpenTaskInterval(),
                    event -> setOpenCircuitsToHalfOpen().setHandler(event1 -> {
                        if(event1.succeeded()){
                            if(event1.result() > 0){
                                log.info("Successfully changed " + event1.result() + " circuits from state open to state half-open");
                            } else {
                                log.info("No open circuits to change state to half-open");
                            }
                        } else {
                            log.error(event1.cause().getMessage());
                        }
                    }));
        }
    }

    private void registerUnlockQueuesTask(){
        boolean unlockQueuesTaskEnabled = getConfig().isUnlockQueuesTaskEnabled();
        vertx.cancelTimer(unlockQueuesTimerId);
        if(unlockQueuesTaskEnabled){
            unlockQueuesTimerId = vertx.setPeriodic(getConfig().getUnlockQueuesTaskInterval(),
                    event -> unlockNextQueue().setHandler(event1 -> {
                        if(event1.succeeded()){
                            if(event1.result() == null){
                                log.info("No locked queues to unlock");
                            } else {
                                log.info("Successfully unlocked queue '" + event1.result() + "'");
                            }
                        } else {
                            log.error("Unable to unlock queue '" + event1.cause().getMessage() + "'");
                        }
                    }));
        }
    }

    private void registerUnlockSampleQueuesTask(){
        boolean unlockSampleQueuesTaskEnabled = getConfig().isUnlockSampleQueuesTaskEnabled();
        vertx.cancelTimer(unlockSampleQueuesTimerId);
        if(unlockSampleQueuesTaskEnabled){
            unlockSampleQueuesTimerId = vertx.setPeriodic(getConfig().getUnlockSampleQueuesTaskInterval(), event -> unlockSampleQueues().setHandler(event1 -> {
                if(event1.succeeded()){
                    if(event1.result() == 0L){
                        log.info("No sample queues to unlock");
                    } else {
                        log.info("Successfully unlocked "+event1.result()+" sample queues");
                    }
                } else {
                    log.error(event1.cause().getMessage());
                }
            }));
        }
    }

    @Override
    public void rulesChanged(List<Rule> rules) {
        log.info("rules have changed, renew rule to circuit mapping");
        List<PatternAndCircuitHash> removedEntries = this.ruleToCircuitMapping.updateRulePatternToCircuitMapping(rules);
        log.info(removedEntries.size() + " mappings have been removed with the update");
        removedEntries.forEach(this::closeAndRemoveCircuit);
    }

    @Override
    public void refresh() {
        log.info("Circuit breaker configuration values have changed. Check periodic tasks");
        registerPeriodicTasks();
    }

    @Override
    public boolean isCircuitCheckEnabled() {
        return configResourceManager.getConfigurationResource().isCircuitCheckEnabled();
    }

    @Override
    public boolean isStatisticsUpdateEnabled() {
        return configResourceManager.getConfigurationResource().isStatisticsUpdateEnabled();
    }

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

        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if(patternAndCircuitHash != null) {
            int errorThresholdPercentage = getConfig().getErrorThresholdPercentage();
            int entriesMaxAgeMS = getConfig().getEntriesMaxAgeMS();
            int minQueueSampleCount = getConfig().getMinQueueSampleCount();
            int maxQueueSampleCount = getConfig().getMaxQueueSampleCount();

            this.queueCircuitBreakerStorage.updateStatistics(patternAndCircuitHash, requestId, currentTS,
                    errorThresholdPercentage, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount,
                    queueResponseType).setHandler(event -> {
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
            log.info("About to close circuit " + patternAndCircuitHash.getPattern().pattern());
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

    private void closeAndRemoveCircuit(PatternAndCircuitHash patternAndCircuitHash) {
        log.info("circuit " + patternAndCircuitHash.getPattern().pattern() + " has been removed. Closing corresponding circuit");
        queueCircuitBreakerStorage.closeAndRemoveCircuit(patternAndCircuitHash).setHandler(event -> {
            if(event.failed()){
                log.error("failed to close circuit " + patternAndCircuitHash.getPattern().pattern());
            }
        });
    }

    @Override
    public Future<Void> closeAllCircuits() {
        log.info("About to close all circuits");
        return queueCircuitBreakerStorage.closeAllCircuits();
    }

    @Override
    public Future<Void> reOpenCircuit(HttpRequest queuedRequest) {
        Future<Void> future = Future.future();
        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if(patternAndCircuitHash != null){
            log.info("About to reopen circuit " + patternAndCircuitHash.getPattern().pattern());
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

    @Override
    public Future<String> unlockNextQueue() {
        log.info("About to unlock the next queue");
        Future<String> future = Future.future();
        queueCircuitBreakerStorage.popQueueToUnlock().setHandler(event -> {
            if(event.failed()){
                future.fail(event.cause().getMessage());
                return;
            }
            String queueToUnlock = event.result();
            if(queueToUnlock != null){
                unlockQueue(queueToUnlock).setHandler(event1 -> {
                    if(event1.failed()){
                        future.fail(event1.cause().getMessage());
                        return;
                    }
                    future.complete(event1.result());
                });
            } else {
                future.complete(null);
            }
        });
        return future;
    }

    private void logQueueUnlockError(String queueToUnlock){
        log.error("Error during unlock of queue '" + queueToUnlock + "'. This queue has been removed from database but not from redisques. This queue must be unlocked manually!");
    }

    @Override
    public Future<Long> setOpenCircuitsToHalfOpen() {
        return queueCircuitBreakerStorage.setOpenCircuitsToHalfOpen();
    }

    @Override
    public Future<Long> unlockSampleQueues() {
        log.info("About to unlock a sample queue for each circuit");
        Future<Long> future = Future.future();
        queueCircuitBreakerStorage.unlockSampleQueues().setHandler(event -> {
            if(event.failed()){
                future.fail(event.cause().getMessage());
                return;
            }
            List<String> queuesToUnlock = event.result();
            if(queuesToUnlock == null || queuesToUnlock.isEmpty()){
                future.complete(0L);
                return;
            }
            final AtomicInteger futureCounter = new AtomicInteger(queuesToUnlock.size());
            List<String> failedFutures = new ArrayList<>();
            for (String queueToUnlock : queuesToUnlock) {
                unlockQueue(queueToUnlock).setHandler(event1 -> {
                    futureCounter.decrementAndGet();
                    if(event1.failed()){
                        failedFutures.add(event1.cause().getMessage());
                    }
                    if(futureCounter.get() == 0){
                        if(failedFutures.size() > 0){
                            future.fail("The following queues could not be unlocked: " + failedFutures);
                        } else {
                            future.complete((long) queuesToUnlock.size());
                        }
                    }
                });
            }
        });
        return future;
    }

    @Override
    public Future<String> unlockQueue(String queueName){
        log.info("About to unlock queue '" + queueName + "'");
        Future<String> future = Future.future();
        vertx.eventBus().send(redisquesAddress, buildDeleteLockOperation(queueName), new Handler<AsyncResult<Message<JsonObject>>>() {
            @Override
            public void handle(AsyncResult<Message<JsonObject>> reply) {
                if(reply.failed()){
                    logQueueUnlockError(queueName);
                    future.fail(queueName);
                    return;
                }
                if(OK.equals(reply.result().body().getString(STATUS))) {
                    future.complete(queueName);
                } else {
                    logQueueUnlockError(queueName);
                    future.fail(queueName);
                }
            }
        });
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
        if(unique == null){
            log.warn("request to " + request.getUri() + " has no unique-id header. Using request uri instead");
            unique = request.getUri();
        }
        return unique;
    }

    private QueueCircuitBreakerConfigurationResource getConfig(){
        return configResourceManager.getConfigurationResource();
    }
}
