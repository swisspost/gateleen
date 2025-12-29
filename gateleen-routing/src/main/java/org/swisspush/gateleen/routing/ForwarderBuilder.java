package org.swisspush.gateleen.routing;

import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.routing.auth.AuthStrategy;

import javax.annotation.Nullable;

import static java.util.Objects.requireNonNull;
import static org.slf4j.LoggerFactory.getLogger;


public class ForwarderBuilder {

    private static final Logger log = getLogger(ForwarderBuilder.class);
    private Vertx vertx;
    private HttpClient client;
    private Rule rule;
    private ResourceStorage storage;
    private LoggingResourceManager loggingResourceManager;
    private LogAppenderRepository logAppenderRepository;
    private MonitoringHandler monitoringHandler;
    private String userProfilePath;
    private AuthStrategy authStrategy;
    private MeterRegistry meterRegistry;

    ForwarderBuilder() {
        /* package-private, because the only one in need to call us is
         * Forwarder.newForwarder(), which is in the same package. */
    }

    public Forwarder build() {
        return new Forwarder(
                requireNonNull(vertx, "vertx"),
                requireNonNull(client, "client"),
                requireNonNull(rule, "rule"),
                requireNonNull(storage, "storage"),
                requireNonNull(loggingResourceManager, "loggingResourceManager"),
                requireNonNull(logAppenderRepository, "logAppenderRepository"),
                monitoringHandler,
                requireNonNull(userProfilePath, "userProfilePath"),
                authStrategy,
                /* TODO don't know if meterRegistry is ok to be null, so I have to
                 *      assume yes. */
                meterRegistry
        );
    }

    public ForwarderBuilder withVertx(Vertx vertx) {
        this.vertx = vertx;
        return this;
    }

    public ForwarderBuilder withClient(HttpClient client) {
        this.client = client;
        return this;
    }

    public ForwarderBuilder withRule(Rule rule) {
        this.rule = rule;
        return this;
    }

    public ForwarderBuilder withStorage(ResourceStorage storage) {
        this.storage = storage;
        return this;
    }

    public ForwarderBuilder withLoggingResourceManager(LoggingResourceManager loggingResourceManager) {
        this.loggingResourceManager = loggingResourceManager;
        return this;
    }

    public ForwarderBuilder withLogAppenderRepository(LogAppenderRepository logAppenderRepository) {
        this.logAppenderRepository = logAppenderRepository;
        return this;
    }

    public ForwarderBuilder withMonitoringHandler(@Nullable MonitoringHandler monitoringHandler) {
        this.monitoringHandler = monitoringHandler;
        return this;
    }

    public ForwarderBuilder withAuthStrategy(@Nullable AuthStrategy authStrategy) {
        this.authStrategy = authStrategy;
        return this;
    }

    public ForwarderBuilder withUserProfilePath(String userProfilePath) {
        this.userProfilePath = userProfilePath;
        return this;
    }

    public ForwarderBuilder withMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

}
