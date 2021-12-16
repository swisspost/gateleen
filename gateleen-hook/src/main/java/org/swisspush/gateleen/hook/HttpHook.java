package org.swisspush.gateleen.hook;

import io.vertx.core.net.ProxyOptions;
import org.joda.time.DateTime;
import org.swisspush.gateleen.core.http.HeaderFunction;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.hook.queueingstrategy.DefaultQueueingStrategy;
import org.swisspush.gateleen.hook.queueingstrategy.QueueingStrategy;
import org.swisspush.gateleen.routing.Rule;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Represents a hook.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class HttpHook {
    public static final String CONNECTION_POOL_SIZE_PROPERTY_NAME = Rule.CONNECTION_POOL_SIZE_PROPERTY_NAME;
    public static final String CONNECTION_MAX_WAIT_QUEUE_SIZE_PROPERTY_NAME = Rule.MAX_WAIT_QUEUE_SIZE_PROPERTY_NAME;
    public static final int CONNECTION_POOL_SIZE_DEFAULT_VALUE = Rule.CONNECTION_POOL_SIZE_DEFAULT_VALUE;
    public static final int CONNECTION_MAX_WAIT_QUEUE_SIZE_DEFAULT_VALUE = Rule.MAX_WAIT_QUEUE_SIZE_DEFAULT_VALUE;
    private String destination;
    private List<String> methods;
    private Pattern headersFilterPattern;
    private Map<Pattern, Integer> translateStatus;
    private DateTime expirationTime;
    private boolean fullUrl = false;
    private QueueingStrategy queueingStrategy = new DefaultQueueingStrategy();
    private Pattern filter = null;
    private int queueExpireAfter;
    private HeaderFunction headerFunction = HeaderFunctions.DO_NOTHING; // default avoids NPE and if-not-null checks
    private HookTriggerType hookTriggerType;
    private boolean listable = false;
    private boolean collection = true;
    private Integer connectionPoolSize = null;
    private Integer maxWaitQueueSize = null;
    private ProxyOptions proxyOptions = null;

    /**
     * Creates a new hook.
     * 
     * @param destination destination
     */
    public HttpHook(String destination) {
        this.destination = destination;
        methods = new ArrayList<>();
        translateStatus = new HashMap<>();
        queueExpireAfter = -1;
        hookTriggerType = HookTriggerType.BEFORE;
    }

    /**
     * The destination of the hook.
     * 
     * @return String
     */
    public String getDestination() {
        return destination;
    }

    /**
     * Sets the destination of the hook.
     * 
     * @param destination destination
     */
    public void setDestination(String destination) {
        this.destination = destination;
    }

    /**
     * Returns the methods which should pass the hook.
     * 
     * @return a list of HTTP methods or empty, if all methods do pass.
     */
    public List<String> getMethods() {
        return methods;
    }

    /**
     * Sets the methods which should pass the hook.
     * 
     * @param methods a list of HTTP methods or empty, if all methods do pass.
     */
    public void setMethods(List<String> methods) {
        this.methods = methods;
    }

    public Pattern getHeadersFilterPattern() {
        return headersFilterPattern;
    }

    public void setHeadersFilterPattern(Pattern headersFilterPattern) {
        this.headersFilterPattern = headersFilterPattern;
    }

    public Map<Pattern, Integer> getTranslateStatus() {
        return translateStatus;
    }

    public void addTranslateStatus(Pattern key, Integer value) {
        this.translateStatus.put(key, value);
    }

    /**
     * @return
     *      Expiration time of this hook. This is null for hooks with infinite
     *      expiration.
     */
    public Optional<DateTime> getExpirationTime() {
        return Optional.ofNullable( expirationTime );
    }

    /**
     * Sets the expiration time of this hook.
     * 
     * @param expirationTime expirationTime
     */
    public void setExpirationTime(DateTime expirationTime) {
        this.expirationTime = expirationTime;
    }

    /**
     * Returns whether the hook forwards using the full initial url or only the appendix.
     * 
     * @return fullUrl
     */
    public boolean isFullUrl() {
        return fullUrl;
    }

    /**
     * Sets whether the hook forwards using the full initial url or only the appendix.
     * 
     * @param fullUrl fullUrl
     */
    public void setFullUrl(boolean fullUrl) {
        this.fullUrl = fullUrl;
    }

    /**
     * Returns the queueing strategy for the hook
     *
     * @return queueingStrategy
     */
    public QueueingStrategy getQueueingStrategy() { return queueingStrategy; }

    /**
     * Sets the queueing strategy for the hook
     *
     * @param queueingStrategy
     */
    public void setQueueingStrategy(QueueingStrategy queueingStrategy) { this.queueingStrategy = queueingStrategy; }

    /**
     * Returns the precompiled pattern, to match
     * a given url.
     * 
     * @return - a precompiled pattern
     */
    public Pattern getFilter() {
        return filter;
    }

    /**
     * Set a regexp to filter the hook. <br >
     * 
     * @param regex - a regular expression
     */
    public void setFilter(String regex) {
        filter = Pattern.compile(regex);
    }

    /**
     * Gets the expiry (x-queue-expire-after header)
     * for the requests in the queue send to the
     * listener.
     * A -1 means that no expiry is set (the header
     * will not be set).
     *
     * @return a value in seconds
     */
    public int getQueueExpireAfter() {
        return queueExpireAfter;
    }

    /**
     * Sets the expiry (x-queue-expire-after header)
     * for the requests in the queue send to the
     * listener.
     * A -1 means that no expiry is set (the header
     * will not be set!).
     *
     * @param queueExpireAfter - a value in seconds
     */
    public void setQueueExpireAfter(int queueExpireAfter) {
        this.queueExpireAfter = queueExpireAfter;
    }

    public HeaderFunction getHeaderFunction() {
        return headerFunction;
    }

    public void setHeaderFunction(HeaderFunction headerFunction) {
        this.headerFunction = headerFunction;
    }

    /**
     * Retuns the trigger type of the hook.
     *
     * @return the trigger type of the hook
     */
    public HookTriggerType getHookTriggerType() {
        return hookTriggerType;
    }

    /**
     * Sets the trigger type of the hook.
     * If nothing is set, the default value is 'before'.
     *
     * @param hookTriggerType the trigger type of the hook
     */
    public void setHookTriggerType(HookTriggerType hookTriggerType) {
        this.hookTriggerType = hookTriggerType;
    }

    /**
     * Indicates if a route hook should be listed
     * for a GET request or not.
     * @return true if the route hook should be listed
     */
    public boolean isListable() {
        return listable;
    }

    /**
     * Sets if a route hook should be listed
     * for a GET request or not.
     *
     * @param listable true if the route hook should be listed
     */
    public void setListable(boolean listable) {
        this.listable = listable;
    }

    /**
     * Indicates if a hook points to a collection (default: true)
     * or not.
     *
     * @return true (default) if hook points to collection.
     */
    public boolean isCollection() {
        return collection;
    }

    /**
     * Sets if a hook points to a collection (default: true)
     * or not.
     *
     * @param collection true (default) if hook points to collection.
     */
    public void setCollection(boolean collection) {
        this.collection = collection;
    }

    /**
     * @return Max count of connections made to configured destination. This may
     *      returning null in case there's no value specified. Callers may catch
     *      that by fall back to a default.
     */
    public Integer getConnectionPoolSize() {
        return connectionPoolSize;
    }

    /**
     * See {@link #getConnectionPoolSize()}.
     */
    public void setConnectionPoolSize(Integer connectionPoolSize) {
        if (connectionPoolSize != null && connectionPoolSize < 1){
            throw new IllegalArgumentException("Values below 1 not valid.");
        }
        this.connectionPoolSize = connectionPoolSize;
    }

    /**
     * @return Maximum number of requests allowed in the wait queue. This may
     *      returning null in case there's no value specified. Callers may catch
     *      that by fall back to a default.
     */
    public Integer getMaxWaitQueueSize() {
        return maxWaitQueueSize;
    }

    /**
     * See {@link #getMaxWaitQueueSize()}.
     */
    public void setMaxWaitQueueSize(Integer maxWaitQueueSize) {
        if (maxWaitQueueSize != null && maxWaitQueueSize < -1){
            throw new IllegalArgumentException("Values below -1 not valid.");
        }
        this.maxWaitQueueSize = maxWaitQueueSize;
    }

    /**
     * Get custom proxy options for this hook
     *
     * @return Custom proxy options or <code>null</code>
     */
    public ProxyOptions getProxyOptions() { return proxyOptions; }

    /**
     * Set custom proxy options for this hook
     *
     * @param proxyOptions the custom proxy options to set
     */
    public void setProxyOptions(ProxyOptions proxyOptions) { this.proxyOptions = proxyOptions; }
}
