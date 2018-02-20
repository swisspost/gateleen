package org.swisspush.gateleen.hook;

import org.joda.time.LocalDateTime;
import org.swisspush.gateleen.core.http.HeaderFunction;
import org.swisspush.gateleen.core.http.HeaderFunctions;
import org.swisspush.gateleen.hook.queueingstrategy.DefaultQueueingStrategy;
import org.swisspush.gateleen.hook.queueingstrategy.QueueingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Represents a hook.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class HttpHook {
    private String destination;
    private List<String> methods;
    private int expireAfter;
    private LocalDateTime expirationTime;
    private boolean fullUrl = false;
    private QueueingStrategy queueingStrategy = new DefaultQueueingStrategy();
    private Pattern filter = null;
    private int queueExpireAfter;
    private HeaderFunction headerFunction = HeaderFunctions.DO_NOTHING; // default avoids NPE and if-not-null checks
    private HookTriggerType hookTriggerType;
    private boolean listable = false;
    private boolean collection = true;

    /**
     * Creates a new hook.
     * 
     * @param destination destination
     */
    public HttpHook(String destination) {
        this.destination = destination;
        methods = new ArrayList<>();
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

    /**
     * Gets the expiry (x-expire-after header)
     * for the requests send to the listener.
     * 
     * @return a value in seconds
     */
    public int getExpireAfter() {
        return expireAfter;
    }

    /**
     * Sets the expiry (x-expire-after header)
     * for the requests send to the listener.
     * 
     * @param expireAfter - a value in seconds
     */
    public void setExpireAfter(int expireAfter) {
        this.expireAfter = expireAfter;
    }

    /**
     * Returns the expiration time of this hook.
     * 
     * @return expirationTime
     */
    public LocalDateTime getExpirationTime() {
        return expirationTime;
    }

    /**
     * Sets the expiration time of this hook.
     * 
     * @param expirationTime expirationTime
     */
    public void setExpirationTime(LocalDateTime expirationTime) {
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
}
