package org.swisspush.gateleen.queue.queuing.circuitbreaker.impl;

import io.vertx.core.*;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.exception.GateleenExceptionFactory;
import org.swisspush.gateleen.core.http.HttpRequest;
import org.swisspush.gateleen.core.lock.Lock;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.LockUtil;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreaker;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.QueueCircuitBreakerStorage;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResource;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.configuration.QueueCircuitBreakerConfigurationResourceManager;
import org.swisspush.gateleen.queue.queuing.circuitbreaker.util.*;
import org.swisspush.gateleen.routing.Rule;
import org.swisspush.gateleen.routing.RuleProvider;
import org.swisspush.gateleen.routing.RuleProvider.RuleChangesObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.swisspush.gateleen.core.util.LockUtil.acquireLock;
import static org.swisspush.redisques.util.RedisquesAPI.*;


/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerImpl implements QueueCircuitBreaker, RuleChangesObserver, Refreshable {

    private final Logger log = LoggerFactory.getLogger(QueueCircuitBreakerImpl.class);

    private final Vertx vertx;
    private final QueueCircuitBreakerStorage queueCircuitBreakerStorage;
    private final QueueCircuitBreakerRulePatternToCircuitMapping ruleToCircuitMapping;
    private final QueueCircuitBreakerConfigurationResourceManager configResourceManager;

    private final Lock lock;
    private final LockUtil lockUtil;

    public static final String OPEN_TO_HALF_OPEN_TASK_LOCK = "openToHalfOpenTask";
    public static final String UNLOCK_QUEUES_TASK_LOCK = "unlockQueuesTask";
    public static final String UNLOCK_SAMPLE_QUEUES_TASK_LOCK = "unlockSampleQueuesTask";

    private static final int DEFAULT_MAX_HEADER_SIZE = 64 * 1024;
    private static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 16 * 1024;

    private final String redisquesAddress;

    private long openToHalfOpenTimerId = -1;
    private long unlockQueuesTimerId = -1;
    private long unlockSampleQueuesTimerId = -1;

    /**
     * Constructor for the QueueCircuitBreakerImpl.
     *
     * @param vertx                                 vertx
     * @param lock                                  the lock implementation
     * @param redisquesAddress                      the event bus address of redisques
     * @param queueCircuitBreakerStorage            the storage
     * @param ruleProvider                          the provider for the rule objects
     * @param ruleToCircuitMapping                  ruleToCircuitMapping helper class
     * @param configResourceManager                 the manager for the configuration resource
     * @param queueCircuitBreakerHttpRequestHandler request handler
     * @param requestHandlerPort                    the port to listen to
     */
    public QueueCircuitBreakerImpl(
        Vertx vertx,
        Lock lock,
        String redisquesAddress,
        QueueCircuitBreakerStorage queueCircuitBreakerStorage,
        RuleProvider ruleProvider,
        GateleenExceptionFactory exceptionFactory,
        QueueCircuitBreakerRulePatternToCircuitMapping ruleToCircuitMapping,
        QueueCircuitBreakerConfigurationResourceManager configResourceManager,
        Handler<HttpServerRequest> queueCircuitBreakerHttpRequestHandler,
        int requestHandlerPort
    ) {
        this.vertx = vertx;
        this.lock = lock;
        this.lockUtil = new LockUtil(exceptionFactory);
        this.redisquesAddress = redisquesAddress;
        this.queueCircuitBreakerStorage = queueCircuitBreakerStorage;
        ruleProvider.registerObserver(this);
        this.ruleToCircuitMapping = ruleToCircuitMapping;
        this.configResourceManager = configResourceManager;

        this.configResourceManager.addRefreshable(this);

        registerPeriodicTasks();

        // in Vert.x 2x 100-continues was activated per default, in vert.x 3x it is off per default.
        HttpServerOptions options = new HttpServerOptions()
                .setHandle100ContinueAutomatically(true)
                .setMaxHeaderSize(DEFAULT_MAX_HEADER_SIZE)
                .setMaxInitialLineLength(DEFAULT_MAX_INITIAL_LINE_LENGTH);

        vertx.createHttpServer(options).requestHandler(queueCircuitBreakerHttpRequestHandler).listen(requestHandlerPort, event -> {
            if (event.succeeded()) {
                log.info("Successfully listening to port {}", requestHandlerPort);
            } else {
                log.error("Unable to listen to port {}. Cannot handle QueueCircuitBreaker http requests", requestHandlerPort);
            }
        });
    }

    private void registerPeriodicTasks() {
        registerOpenToHalfOpenTask();
        registerUnlockQueuesTask();
        registerUnlockSampleQueuesTask();
    }

    private String createToken(String appendix) {
        return Address.instanceAddress() + "_" + System.currentTimeMillis() + "_" + appendix;
    }

    private long getLockExpiry(int taskInterval) {
        if (taskInterval <= 1) {
            return 1;
        }
        return taskInterval / 2;
    }

    private void registerOpenToHalfOpenTask() {
        boolean openToHalfOpenTaskEnabled = getConfig().isOpenToHalfOpenTaskEnabled();
        int openToHalfOpenTaskInterval = getConfig().getOpenToHalfOpenTaskInterval();
        vertx.cancelTimer(openToHalfOpenTimerId);
        if (openToHalfOpenTaskEnabled) {
            log.info("About to register periodic open to half-open task execution every {}ms", openToHalfOpenTaskInterval);
            openToHalfOpenTimerId = vertx.setPeriodic(openToHalfOpenTaskInterval,
                    event -> {
                        final String token = createToken(OPEN_TO_HALF_OPEN_TASK_LOCK);
                        acquireLock(this.lock, OPEN_TO_HALF_OPEN_TASK_LOCK, token, getLockExpiry(openToHalfOpenTaskInterval), log).onComplete(lockEvent -> {
                            if (lockEvent.succeeded()) {
                                if (lockEvent.result()) {
                                    setOpenCircuitsToHalfOpen().onComplete(event1 -> {
                                        if (event1.succeeded()) {
                                            if (event1.result() > 0) {
                                                log.info("Successfully changed {} circuits from state open to state half-open", event1.result());
                                            } else {
                                                log.debug("No open circuits to change state to half-open");
                                            }
                                        } else {
                                            log.error(event1.cause().getMessage());
                                            lockUtil.releaseLock(this.lock, OPEN_TO_HALF_OPEN_TASK_LOCK, token, log);
                                        }
                                    });
                                }
                            } else {
                                log.error("Could not acquire lock '{}'. Message: {}", OPEN_TO_HALF_OPEN_TASK_LOCK, lockEvent.cause().getMessage());
                            }
                        });
                    });
        } else {
            log.info("Not going to register periodic open to half-open task execution");
        }
    }

    private void registerUnlockQueuesTask() {
        boolean unlockQueuesTaskEnabled = getConfig().isUnlockQueuesTaskEnabled();
        int unlockQueuesTaskInterval = getConfig().getUnlockQueuesTaskInterval();
        vertx.cancelTimer(unlockQueuesTimerId);
        if (unlockQueuesTaskEnabled) {
            log.info("About to register periodic queues unlock task execution every {}ms", unlockQueuesTaskInterval);
            unlockQueuesTimerId = vertx.setPeriodic(unlockQueuesTaskInterval,
                    event -> {
                        final String token = createToken(UNLOCK_QUEUES_TASK_LOCK);
                        acquireLock(this.lock, UNLOCK_QUEUES_TASK_LOCK, token, getLockExpiry(unlockQueuesTaskInterval), log).onComplete(lockEvent -> {
                            if (lockEvent.succeeded()) {
                                if (lockEvent.result()) {
                                    unlockNextQueue().onComplete(event1 -> {
                                        if (event1.succeeded()) {
                                            if (event1.result() == null) {
                                                log.debug("No locked queues to unlock");
                                            } else {
                                                log.debug("Successfully unlocked queue '{}'", event1.result());
                                            }
                                        } else {
                                            log.error("Unable to unlock queue '{}'", event1.cause().getMessage());
                                            lockUtil.releaseLock(this.lock, UNLOCK_QUEUES_TASK_LOCK, token, log);
                                        }
                                    });
                                }
                            } else {
                                log.error("Could not acquire lock '{}'. Message: {}", UNLOCK_QUEUES_TASK_LOCK, lockEvent.cause().getMessage());
                            }
                        });
                    });
        } else {
            log.info("Not going to register periodic queues unlock task execution");
        }
    }

    private void registerUnlockSampleQueuesTask() {
        boolean unlockSampleQueuesTaskEnabled = getConfig().isUnlockSampleQueuesTaskEnabled();
        int unlockSampleQueuesTaskInterval = getConfig().getUnlockSampleQueuesTaskInterval();
        vertx.cancelTimer(unlockSampleQueuesTimerId);
        if (unlockSampleQueuesTaskEnabled) {
            log.info("About to register periodic unlock sample queues task execution every {}ms", unlockSampleQueuesTaskInterval);
            unlockSampleQueuesTimerId = vertx.setPeriodic(unlockSampleQueuesTaskInterval, event -> {
                final String token = createToken(UNLOCK_SAMPLE_QUEUES_TASK_LOCK);
                acquireLock(this.lock, UNLOCK_SAMPLE_QUEUES_TASK_LOCK, token, getLockExpiry(unlockSampleQueuesTaskInterval), log).onComplete(lockEvent -> {
                    if (lockEvent.succeeded()) {
                        if (lockEvent.result()) {
                            unlockSampleQueues().onComplete(event1 -> {
                                if (event1.succeeded()) {
                                    if (event1.result() == 0L) {
                                        log.debug("No sample queues to unlock");
                                    } else {
                                        log.info("Successfully unlocked {} sample queues", event1.result());
                                    }
                                } else {
                                    log.error(event1.cause().getMessage());
                                    lockUtil.releaseLock(this.lock, UNLOCK_SAMPLE_QUEUES_TASK_LOCK, token, log);
                                }
                            });
                        }
                    } else {
                        log.error("Could not acquire lock '{}'. Message: {}", UNLOCK_SAMPLE_QUEUES_TASK_LOCK, lockEvent.cause().getMessage());
                    }
                });
            });
        } else {
            log.info("Not going to register periodic unlock sample queues task execution");
        }
    }

    @Override
    public void rulesChanged(List<Rule> rules) {
        log.info("rules have changed, renew rule to circuit mapping");
        List<PatternAndCircuitHash> removedEntries = this.ruleToCircuitMapping.updateRulePatternToCircuitMapping(rules);
        log.info("{} mappings have been removed with the update", removedEntries.size());
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
    public Future<QueueCircuitState> handleQueuedRequest(String queueName, HttpRequest queuedRequest) {
        Promise<QueueCircuitState> promise = Promise.promise();
        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if (patternAndCircuitHash != null) {
            this.queueCircuitBreakerStorage.getQueueCircuitState(patternAndCircuitHash).onComplete(event -> {
                if (event.failed()) {
                    promise.fail(event.cause());
                } else {
                    promise.complete(event.result());
                    if (QueueCircuitState.OPEN == event.result()) {
                        lockQueueSync(queueName, queuedRequest);
                    }
                }
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(promise, queueName, queuedRequest);
        }
        return promise.future();
    }

    @Override
    public Future<Void> updateStatistics(String queueName, HttpRequest queuedRequest, QueueResponseType queueResponseType) {
        Promise<Void> promise = Promise.promise();
        String requestId = getRequestUniqueId(queuedRequest);
        long currentTS = System.currentTimeMillis();

        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if (patternAndCircuitHash != null) {
            int errorThresholdPercentage = getConfig().getErrorThresholdPercentage();
            int entriesMaxAgeMS = getConfig().getEntriesMaxAgeMS();
            int minQueueSampleCount = getConfig().getMinQueueSampleCount();
            int maxQueueSampleCount = getConfig().getMaxQueueSampleCount();

            this.queueCircuitBreakerStorage.updateStatistics(patternAndCircuitHash, requestId, currentTS,
                    errorThresholdPercentage, entriesMaxAgeMS, minQueueSampleCount, maxQueueSampleCount,
                    queueResponseType).onComplete(event -> {
                if (event.failed()) {
                    promise.fail(event.cause());
                } else {
                    if (UpdateStatisticsResult.OPENED == event.result()) {
                        log.warn("circuit '{}' has been opened", patternAndCircuitHash.getPattern().pattern());
                        lockQueueSync(queueName, queuedRequest);
                    }
                    promise.complete();
                }
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(promise, queueName, queuedRequest);
        }
        return promise.future();
    }

    @Override
    public Future<Void> closeCircuit(HttpRequest queuedRequest) {
        Promise<Void> promise = Promise.promise();
        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if (patternAndCircuitHash != null) {
            log.info("About to close circuit {}", patternAndCircuitHash.getPattern().pattern());
            queueCircuitBreakerStorage.closeCircuit(patternAndCircuitHash).onComplete(event -> {
                if (event.failed()) {
                    promise.fail(event.cause());
                    return;
                }
                log.debug("circuit '{}' has been closed", patternAndCircuitHash.getPattern().pattern());
                promise.complete();
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(promise, null, queuedRequest);
        }
        return promise.future();
    }

    private void closeAndRemoveCircuit(PatternAndCircuitHash patternAndCircuitHash) {
        log.info("circuit {} has been removed. Closing corresponding circuit", patternAndCircuitHash.getPattern().pattern());
        queueCircuitBreakerStorage.closeAndRemoveCircuit(patternAndCircuitHash).onComplete(event -> {
            if (event.failed()) {
                log.error("failed to close circuit {}", patternAndCircuitHash.getPattern().pattern());
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
        Promise<Void> promise = Promise.promise();
        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if (patternAndCircuitHash != null) {
            log.info("About to reopen circuit {}", patternAndCircuitHash.getPattern().pattern());
            queueCircuitBreakerStorage.reOpenCircuit(patternAndCircuitHash).onComplete(event -> {
                if (event.failed()) {
                    promise.fail(event.cause());
                    return;
                }
                log.info("circuit '{}' has been reopened", patternAndCircuitHash.getPattern().pattern());
                promise.complete();
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(promise, null, queuedRequest);
        }
        return promise.future();
    }

    @Override
    public Future<Void> lockQueue(String queueName, HttpRequest queuedRequest) {
        Promise<Void> promise = Promise.promise();

        PatternAndCircuitHash patternAndCircuitHash = getPatternAndCircuitHashFromRequest(queuedRequest);
        if (patternAndCircuitHash != null) {
            queueCircuitBreakerStorage.lockQueue(queueName, patternAndCircuitHash).onComplete(event -> {
                if (event.failed()) {
                    promise.fail(event.cause());
                    return;
                }
                vertx.eventBus().request(redisquesAddress, buildPutLockOperation(queueName, "queue_circuit_breaker"),
                        (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                            if (reply.failed()) {
                                promise.fail(reply.cause());
                                return;
                            }
                            if (OK.equals(reply.result().body().getString(STATUS))) {
                                log.info("locked queue '{}' because the circuit '{}' is open", queueName,
                                        patternAndCircuitHash.getPattern().pattern());
                                promise.complete();
                            } else {
                                promise.fail("failed to lock queue '" + queueName
                                        + "'. Queue should have been locked, because the circuit '"
                                        + patternAndCircuitHash.getPattern().pattern() + "' is open");
                            }
                        });
            });
        } else {
            failWithNoRuleToCircuitMappingMessage(promise, queueName, queuedRequest);
        }
        return promise.future();
    }

    @Override
    public Future<String> unlockNextQueue() {
        log.debug("About to unlock the next queue");
        Promise<String> promise = Promise.promise();
        queueCircuitBreakerStorage.popQueueToUnlock().onComplete(event -> {
            if (event.failed()) {
                promise.fail(event.cause().getMessage());
                return;
            }
            String queueToUnlock = event.result();
            if (queueToUnlock != null) {
                unlockQueue(queueToUnlock).onComplete(event1 -> {
                    if (event1.failed()) {
                        promise.fail(event1.cause().getMessage());
                        return;
                    }
                    promise.complete(event1.result());
                });
            } else {
                promise.complete(null);
            }
        });
        return promise.future();
    }

    private void logQueueUnlockError(String queueToUnlock, String errorMessage) {
        log.error("Error during unlock of queue '{}'. This queue has been removed from database " +
                "but not from redisques. This queue must be unlocked manually! Message: {}", queueToUnlock, errorMessage);
    }

    @Override
    public Future<Long> setOpenCircuitsToHalfOpen() {
        return queueCircuitBreakerStorage.setOpenCircuitsToHalfOpen();
    }

    @Override
    public Future<Long> unlockSampleQueues() {
        log.debug("About to unlock a sample queue for each circuit");
        Promise<Long> promise = Promise.promise();
        queueCircuitBreakerStorage.unlockSampleQueues().onComplete(event -> {
            if (event.failed()) {
                promise.fail(event.cause().getMessage());
                return;
            }
            Response queuesToUnlock = event.result();
            if (queuesToUnlock == null || queuesToUnlock.size() == 0) {
                promise.complete(0L);
                return;
            }
            final AtomicInteger futureCounter = new AtomicInteger(queuesToUnlock.size());
            List<String> failedFutures = new ArrayList<>();
            for (Response queueToUnlock : queuesToUnlock) {
                log.info("About to unlock sample queue '{}'", queueToUnlock);
                unlockQueue(queueToUnlock.toString()).onComplete(event1 -> {
                    futureCounter.decrementAndGet();
                    if (event1.failed()) {
                        failedFutures.add(event1.cause().getMessage());
                    }
                    if (futureCounter.get() == 0) {
                        if (!failedFutures.isEmpty()) {
                            promise.fail("The following queues could not be unlocked: " + failedFutures);
                        } else {
                            promise.complete((long) queuesToUnlock.size());
                        }
                    }
                });
            }
        });
        return promise.future();
    }

    @Override
    public Future<String> unlockQueue(String queueName) {
        log.info("About to unlock queue '{}'", queueName);
        Promise<String> promise = Promise.promise();
        vertx.eventBus().request(redisquesAddress, buildDeleteLockOperation(queueName), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if (reply.failed()) {
                logQueueUnlockError(queueName, reply.cause().getMessage());
                promise.fail(queueName);
                return;
            }
            if (OK.equals(reply.result().body().getString(STATUS))) {
                promise.complete(queueName);
            } else {
                logQueueUnlockError(queueName, "Got reply with status value '" + reply.result().body().getString(STATUS) + "'");
                promise.fail(queueName);
            }
        });
        return promise.future();
    }

    private void lockQueueSync(String queueName, HttpRequest queuedRequest) {
        lockQueue(queueName, queuedRequest).onComplete(event -> {
            if (event.failed()) {
                log.warn(event.cause().getMessage());
            }
        });
    }

    private void failWithNoRuleToCircuitMappingMessage(Promise promise, String queueName, HttpRequest request) {
        if (queueName == null) {
            promise.fail("no rule to circuit mapping found for uri " + request.getUri());
        } else {
            promise.fail("no rule to circuit mapping found for queue '" + queueName + "' and uri " + request.getUri());
        }
    }

    private PatternAndCircuitHash getPatternAndCircuitHashFromRequest(HttpRequest request) {
        return this.ruleToCircuitMapping.getCircuitFromRequestUri(request.getUri());
    }

    private String getRequestUniqueId(HttpRequest request) {
        String unique = request.getHeaders().get("x-rp-unique_id");
        if (unique == null) {
            log.info("request to {} has no unique-id header. Using request uri instead", request.getUri());
            unique = request.getUri();
        }
        return unique;
    }

    private QueueCircuitBreakerConfigurationResource getConfig() {
        return configResourceManager.getConfigurationResource();
    }
}
