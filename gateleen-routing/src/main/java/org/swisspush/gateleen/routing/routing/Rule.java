package org.swisspush.gateleen.routing.routing;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class Rule {
    private String scheme;
    private String host;
    private String metricName;
    private int port;
    private String path;
    private int poolSize;
    private boolean keepAlive;
    private boolean expandOnBackend;
    private boolean storageExpand;
    private String urlPattern;
    private int timeout;
    private String username;
    private String password;
    private Map<Pattern, Integer> translateStatus = new LinkedHashMap<>();
    private int logExpiry;
    private String[] methods;
    private String[] profile;
    private Map<String, String> staticHeaders = new HashMap<>();
    private String storage;

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

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public boolean isExpandOnBackend() { return expandOnBackend; }

    public void setExpandOnBackend(boolean expandOnBackend) {
        this.expandOnBackend = expandOnBackend;
    }

    public boolean isStorageExpand() { return storageExpand; }

    public void setStorageExpand(boolean storageExpand) { this.storageExpand = storageExpand; }

    public String getUrlPattern() {
        return urlPattern;
    }

    public void setUrlPattern(String urlPattern) {
        this.urlPattern = urlPattern;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public Map<String, String> getStaticHeaders() {
        return staticHeaders;
    }

    public void addStaticHeaders(Map<String, String> staticHeaders) {
        this.staticHeaders.putAll(staticHeaders);
    }

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }
}