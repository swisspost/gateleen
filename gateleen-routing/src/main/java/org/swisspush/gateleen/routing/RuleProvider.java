package org.swisspush.gateleen.routing;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.Address;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.validation.ValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provides a central access to the routing rules.
 * Loads routing rules from storage and creates {@link Rule} instances. Also provides the ability to
 * register for routing rules changes.
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RuleProvider {

    private Logger log = LoggerFactory.getLogger(RuleProvider.class);

    private String rulesPath;
    private String routingRulesSchema;
    private ResourceStorage storage;
    final Map<String, Object> properties;

    private List<RuleChangesObserver> observers = new ArrayList<>();

    public RuleProvider(Vertx vertx, String rulesPath, ResourceStorage storage, Map<String, Object> properties) {
        this.rulesPath = rulesPath;
        this.storage = storage;
        this.properties = properties;

        routingRulesSchema = ResourcesUtils.loadResource("gateleen_routing_schema_routing_rules", true);

        notifyRuleChangesObservers();

        log.info("Register on vertx event bus to receive routing rules updates");
        vertx.eventBus().consumer(Address.RULE_UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> {
            notifyRuleChangesObservers();
        });
    }

    /**
     * Implementations of this interface are notified when the routing rules have changed. Use {@link RuleProvider#registerObserver(RuleChangesObserver)} to
     * register for updates
     */
    public interface RuleChangesObserver {
        void rulesChanged(List<Rule> rules);
    }

    /**
     * Registers an observer to be notified on routing rules changes.
     *
     * @param observer Observer to be notified on routing rules changes
     */
    public void registerObserver(RuleChangesObserver observer){
        observers.add(observer);
    }

    /**
     * Get the routing rules from storage (async)
     *
     * @return a {@link Future} containing a list of {@link Rule} objects (when successful)
     */
    public Future<List<Rule>> getRules(){
        Future<List<Rule>> future = Future.future();
        storage.get(rulesPath, buffer -> {
            if (buffer != null) {
                try {
                    List<Rule> rules = new RuleFactory(properties, routingRulesSchema).parseRules(buffer);
                    future.complete(rules);
                } catch (ValidationException e) {
                    log.error("Could parse routing rules", e);
                    future.fail(e);
                }
            } else {
                future.fail("Could not get URL '" + (rulesPath == null ? "<null>" : rulesPath) + "' (getting rules).");
            }
        });
        return future;
    }

    private void notifyRuleChangesObservers(){
        getRules().setHandler(event -> {
            if(event.failed()){
                log.error(event.cause().getMessage());
            } else {
                log.info("About to notify observers interested in changed routing rules");
                for (RuleChangesObserver observer : observers) {
                    observer.rulesChanged(event.result());
                }
            }
        });
    }
}
