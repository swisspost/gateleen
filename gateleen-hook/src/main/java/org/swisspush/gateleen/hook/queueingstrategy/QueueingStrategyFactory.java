package org.swisspush.gateleen.hook.queueingstrategy;

import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * Factory class to build {@link QueueingStrategy} instances from hook configurations.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueingStrategyFactory {

    private static final String QUEUEING_STRATEGY_PROPERTY = "queueingStrategy";

    private static Logger LOG = LoggerFactory.getLogger(QueueingStrategyFactory.class);

    private enum Strategy {
        DISCARD_PAYLOAD("discardPayload"),
        REDUCED_PROPAGATION("reducedPropagation");

        private final String type;

        Strategy(String type) { this.type = type; }

        public String getType() { return type; }

        public static Strategy fromString(String op){
            for (Strategy strategy : values()) {
                if(strategy.getType().equalsIgnoreCase(op)){
                    return strategy;
                }
            }
            return null;
        }
    }

    private QueueingStrategyFactory() {
        // prevent instantiation
    }

    /**
     * Returns a {@link QueueingStrategy} based on the provided hookConfiguration. When <code>null</code> is provided
     * or the hookConfiguration contains invalid configuration relating the 'queueingStrategy', a {@link DefaultQueueingStrategy}
     * instance will be returned.
     *
     * @param hookConfiguration the hook configuration containing the 'queueingStrategy' configuration values
     * @return A {@link QueueingStrategy} based on the provided hookConfiguration
     */
    public static QueueingStrategy buildQueueStrategy(JsonObject hookConfiguration) {
        QueueingStrategy queueingStrategy = new DefaultQueueingStrategy();
        if(hookConfiguration == null){
            return queueingStrategy;
        }

        JsonObject hook = hookConfiguration.getJsonObject("hook");
        if(hook == null || !hook.containsKey(QUEUEING_STRATEGY_PROPERTY)){
            return queueingStrategy;
        }

        JsonObject queueingStrategyConfig = hook.getJsonObject(QUEUEING_STRATEGY_PROPERTY);
        Strategy strategy = Strategy.fromString(queueingStrategyConfig.getString("type"));

        if(strategy == null){
            LOG.warn("Invalid 'queueingStrategy' configuration found: " + queueingStrategyConfig.encode() + ". Using DefaultQueueingStrategy instead");
            return queueingStrategy;
        }

        switch (strategy){
            case DISCARD_PAYLOAD:
                queueingStrategy = new DiscardPayloadQueueingStrategy();
                break;
            case REDUCED_PROPAGATION:
                queueingStrategy = buildReducedPropagationQueueingStrategy(queueingStrategyConfig);
                break;
            default:
            LOG.warn("Unknown strategy '"+strategy+"'. Using DefaultQueueingStrategy instead");
        }

        return queueingStrategy;
    }

    private static QueueingStrategy buildReducedPropagationQueueingStrategy(JsonObject queueingStrategyConfig){
        try{
            Long interval = queueingStrategyConfig.getLong("interval");
            if(interval != null){
                return new ReducedPropagationQueueingStrategy(interval);
            } else {
                LOG.warn("Got a 'ReducedPropagationQueueingStrategy' configuration with an invalid interval value: " + queueingStrategyConfig.encode());
                return new DefaultQueueingStrategy();
            }
        }catch (Exception ex){
            LOG.warn("Got a 'ReducedPropagationQueueingStrategy' configuration with an invalid interval value: " + queueingStrategyConfig.encode());
            return new DefaultQueueingStrategy();
        }
    }
}
