package org.swisspush.gateleen.core.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.ResourcesUtils;
import org.swisspush.gateleen.core.util.StringUtils;

/**
 * Abstract base class for configuration resource consumers which handles initialization and registration.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public abstract class ConfigurationResourceConsumer implements ConfigurationResourceObserver {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationResourceConsumer.class);

    private final ConfigurationResourceManager configurationResourceManager;
    private final String configResourceUri;
    private final String schemaResourceName;

    public ConfigurationResourceConsumer(ConfigurationResourceManager configurationResourceManager,
                                         String configResourceUri,
                                         String schemaResourceName) {
        this.configurationResourceManager = configurationResourceManager;
        this.configResourceUri = configResourceUri;
        this.schemaResourceName = schemaResourceName;

        initialize();
    }

    private void initialize() {
        if (configurationResourceManager != null
                && StringUtils.isNotEmptyTrimmed(configResourceUri)
                && StringUtils.isNotEmptyTrimmed(schemaResourceName)) {
            log.info("Register resource and observer for config resource uri {}", configResourceUri);
            String schema = ResourcesUtils.loadResource(schemaResourceName, true);
            configurationResourceManager.registerResource(configResourceUri, schema);
            configurationResourceManager.registerObserver(this, configResourceUri);
        } else {
            log.warn("No configuration resource manager and/or no configuration resource uri and/or no schema " +
                    "resource name defined. Not using this feature in this case");
        }
    }

    public ConfigurationResourceManager configurationResourceManager() {
        return configurationResourceManager;
    }

    public String configResourceUri() {
        return configResourceUri;
    }

    public String schemaResourceName() {
        return schemaResourceName;
    }
}
