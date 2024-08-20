/*
 * Copyright 2024 by Swiss Post, Information Technology
 */

package org.swisspush.gateleen.hook;

import io.vertx.core.json.JsonObject;
import io.vertx.core.net.ProxyOptions;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Represents the configuration for a destination in the routing or hook system.
 *
 * @author almeidast
 */
public class DestinationConfig {
    private String destinationUri;
    private Optional<ProxyOptions> proxyOptions;
    private boolean fullUrl;
    private Integer connectionPoolSize;
    private Integer maxWaitQueueSize;
    private Integer timeout;

    /**
     * Constructor for creating a DestinationConfig with the given parameters.
     *
     * @param destinationUri      The URI of the destination.
     * @param proxyOptions        Optional proxy settings for this destination.
     * @param fullUrl             Indicates whether to use the full URL when forwarding.
     * @param connectionPoolSize  The size of the connection pool.
     * @param maxWaitQueueSize    The maximum number of requests in the wait queue.
     * @param timeout             The timeout for requests in milliseconds.
     */
    public DestinationConfig(String destinationUri, @Nullable ProxyOptions proxyOptions, boolean fullUrl,
                             @Nullable Integer connectionPoolSize, @Nullable Integer maxWaitQueueSize, @Nullable Integer timeout) {
        this.destinationUri = destinationUri;
        this.proxyOptions = Optional.ofNullable(proxyOptions);
        this.fullUrl = fullUrl;
        this.connectionPoolSize = connectionPoolSize;
        this.maxWaitQueueSize = maxWaitQueueSize;
        this.timeout = timeout;
    }

    /**
     * Gets the destination URI.
     *
     * @return The destination URI.
     */
    public String getDestinationUri() {
        return destinationUri;
    }

    /**
     * Gets the proxy options for this destination, if any.
     *
     * @return An Optional containing the proxy options if present.
     */
    public Optional<ProxyOptions> getProxyOptions() {
        return proxyOptions;
    }

    /**
     * Returns whether the full URL should be used when forwarding.
     *
     * @return true if the full URL should be used, false otherwise.
     */
    public boolean isFullUrl() {
        return fullUrl;
    }

    /**
     * Gets the connection pool size.
     *
     * @return The connection pool size, or null if not set.
     */
    @Nullable
    public Integer getConnectionPoolSize() {
        return connectionPoolSize;
    }

    /**
     * Gets the maximum number of requests allowed in the wait queue.
     *
     * @return The maximum wait queue size, or null if not set.
     */
    @Nullable
    public Integer getMaxWaitQueueSize() {
        return maxWaitQueueSize;
    }

    /**
     * Gets the timeout for requests in milliseconds.
     *
     * @return The timeout, or null if not set.
     */
    @Nullable
    public Integer getTimeout() {
        return timeout;
    }

    /**
     * Creates a DestinationConfig from a JSON object.
     *
     * @param jsonObject The JSON object containing the configuration.
     * @return A DestinationConfig object.
     */
    public static DestinationConfig fromJson(JsonObject jsonObject) {
        String destinationUri = jsonObject.getString("destinationUri");
        ProxyOptions proxyOptions = jsonObject.containsKey("proxyOptions")
                ? new ProxyOptions(jsonObject.getJsonObject("proxyOptions"))
                : null;
        boolean fullUrl = jsonObject.getBoolean("fullUrl", false);
        Integer connectionPoolSize = jsonObject.getInteger("connectionPoolSize");
        Integer maxWaitQueueSize = jsonObject.getInteger("maxWaitQueueSize");
        Integer timeout = jsonObject.getInteger("timeout");

        return new DestinationConfig(destinationUri, proxyOptions, fullUrl, connectionPoolSize, maxWaitQueueSize, timeout);
    }

    /**
     * Converts this DestinationConfig to a JSON object.
     *
     * @return A JSON object representing this configuration.
     */
    public JsonObject toJson() {
        JsonObject jsonObject = new JsonObject()
                .put("destinationUri", destinationUri)
                .put("fullUrl", fullUrl);

        if (proxyOptions.isPresent()) {
            jsonObject.put("proxyOptions", proxyOptions.get().toJson());
        }

        if (connectionPoolSize != null) {
            jsonObject.put("connectionPoolSize", connectionPoolSize);
        }

        if (maxWaitQueueSize != null) {
            jsonObject.put("maxWaitQueueSize", maxWaitQueueSize);
        }

        if (timeout != null) {
            jsonObject.put("timeout", timeout);
        }

        return jsonObject;
    }
}
