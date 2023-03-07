package org.swisspush.gateleen.routing;

import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.ProxyOptions;
import org.swisspush.gateleen.core.http.HeaderFunction;
import org.swisspush.gateleen.core.http.HeaderFunctions;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class Rule {
    public static final String CONNECTION_POOL_SIZE_PROPERTY_NAME = "connectionPoolSize";
    public static final String MAX_WAIT_QUEUE_SIZE_PROPERTY_NAME = "maxWaitQueueSize";
    public static final String CONNECTION_TIMEOUT_SEC_PROPERTY_NAME = "timeout";
    public static final int CONNECTION_POOL_SIZE_DEFAULT_VALUE = 50;
    public static final int MAX_WAIT_QUEUE_SIZE_DEFAULT_VALUE = -1;
    public static final int CONNECTION_TIMEOUT_SEC_DEFAULT_VALUE = 30;
    private String scheme;
    private String host;
    private String metricName;
    private int port;
    private String portWildcard;
    private String path;
    private int poolSize;
    private int maxWaitQueueSize;
    private boolean keepAlive;
    private int keepAliveTimeout;
    private boolean expandOnBackend;
    private boolean deltaOnBackend;
    private boolean storageExpand;
    private String urlPattern;
    private Pattern headersFilterPattern;
    private int timeout;
    private String oAuthId;
    private String basicAuthUsername;
    private String basicAuthPassword;
    private Map<Pattern, Integer> translateStatus = new LinkedHashMap<>();
    private int logExpiry;
    private String[] methods;
    private String[] profile;
    private HeaderFunction headerFunction = HeaderFunctions.DO_NOTHING; // default avoids NPE and if-not-null checks
    private ProxyOptions proxyOptions;

    private String storage;

    public String getRuleIdentifier() {
        if(metricName != null){
            return metricName;
        }
        return urlPattern;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPortWildcard() {
        return portWildcard;
    }

    public void setPortWildcard(String portWildcard) {
        this.portWildcard = portWildcard;
    }

    public boolean hasPortWildcard() {
        return portWildcard != null;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public int getMaxWaitQueueSize() {
        return maxWaitQueueSize;
    }

    public void setMaxWaitQueueSize(int maxWaitQueueSize) {
        this.maxWaitQueueSize = maxWaitQueueSize;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    /**
     * Gets the keep-alive timeout in seconds, see {@link HttpClientOptions#DEFAULT_KEEP_ALIVE_TIMEOUT}
     */
    public int getKeepAliveTimeout() {
        return keepAliveTimeout;
    }

    /**
     * Sets the keep-alive timeout in seconds, see {@link HttpClientOptions#DEFAULT_KEEP_ALIVE_TIMEOUT}
     */
    public void setKeepAliveTimeout(int keepAliveTimeoutSeconds) {
        this.keepAliveTimeout = keepAliveTimeoutSeconds;
    }

    public boolean isExpandOnBackend() { return expandOnBackend; }

    public void setExpandOnBackend(boolean expandOnBackend) {
        this.expandOnBackend = expandOnBackend;
    }

    public boolean isDeltaOnBackend() { return deltaOnBackend; }

    public void setDeltaOnBackend(boolean deltaOnBackend) {
        this.deltaOnBackend = deltaOnBackend;
    }

    public boolean isStorageExpand() { return storageExpand; }

    public void setStorageExpand(boolean storageExpand) { this.storageExpand = storageExpand; }

    public String getUrlPattern() {
        return urlPattern;
    }

    public void setUrlPattern(String urlPattern) {
        this.urlPattern = urlPattern;
    }

    public Pattern getHeadersFilterPattern() {
        return headersFilterPattern;
    }

    public void setHeadersFilterPattern(Pattern headersFilterPattern) {
        this.headersFilterPattern = headersFilterPattern;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getOAuthId() {
        return oAuthId;
    }

    public void setOAuthId(String oAuthId) {
        this.oAuthId = oAuthId;
    }

    public String getBasicAuthUsername() {
        return basicAuthUsername;
    }

    public void setBasicAuthUsername(String basicAuthUsername) {
        this.basicAuthUsername = basicAuthUsername;
    }

    public String getBasicAuthPassword() {
        return basicAuthPassword;
    }

    public void setBasicAuthPassword(String basicAuthPassword) {
        this.basicAuthPassword = basicAuthPassword;
    }

    public Map<Pattern, Integer> getTranslateStatus() {
        return translateStatus;
    }

    public void addTranslateStatus(Map<Pattern, Integer> translateStatus) {
        this.translateStatus.putAll(translateStatus);
    }

    public int getLogExpiry() {
        return logExpiry;
    }

    public void setLogExpiry(int logExpiry) {
        this.logExpiry = logExpiry;
    }

    public String[] getMethods() {
        return methods;
    }

    public void setMethods(String[] methods) {
        this.methods = Arrays.copyOf(methods, methods.length);
    }

    public String[] getProfile() {
        return profile;
    }

    public void setProfile(String[] profile) {
        this.profile = Arrays.copyOf(profile, profile.length);
    }

    public HeaderFunction getHeaderFunction() {
        return headerFunction;
    }

    public void setHeaderFunction(HeaderFunction headerFunction) {
        this.headerFunction = headerFunction;
    }

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public ProxyOptions getProxyOptions() {
        return proxyOptions;
    }

    public void setProxyOptions(ProxyOptions proxyOptions) {
        this.proxyOptions = proxyOptions;
    }

    public HttpClientOptions buildHttpClientOptions() {
        final HttpClientOptions options = new HttpClientOptions()
                .setDefaultHost(getHost())
                .setDefaultPort(getPort())
                .setMaxPoolSize(getPoolSize())
                .setConnectTimeout(getTimeout())
                .setKeepAlive(isKeepAlive())
                .setKeepAliveTimeout(getKeepAliveTimeout())
                .setPipelining(false)
                .setMaxWaitQueueSize(getMaxWaitQueueSize());
        if ("https".equals(getScheme())) {
            options.setSsl(true).setVerifyHost(false).setTrustAll(true);
        }

        if (getProxyOptions() != null) {
            options.setProxyOptions(getProxyOptions());
        }

        return options;
    }

}
