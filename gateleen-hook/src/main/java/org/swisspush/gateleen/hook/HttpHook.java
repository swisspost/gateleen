package org.swisspush.gateleen.hook;

import org.joda.time.LocalDateTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private Pattern filter = null;
    private int queueExpireAfter;
    private Map<String, String> staticHeaders = null;

    /**
     * Creates a new hook.
     * 
     * @param destination destination
     */
    public HttpHook(String destination) {
        this.destination = destination;
        methods = new ArrayList<>();
        queueExpireAfter = -1;
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

    /**
     * Adds a new map with static headers.
     *
     * @param staticHeaders - a map with static headers
     */
    public void addStaticHeaders(Map<String, String> staticHeaders) {
        this.staticHeaders = staticHeaders;
    }

    /**
     * Returns the map with the static headers for this
     * hook.
     *
     * @return map with static headers or null if
     * no static headers were defined
     */
    public Map<String, String> getStaticHeaders() {
        return this.staticHeaders;
    }
}
