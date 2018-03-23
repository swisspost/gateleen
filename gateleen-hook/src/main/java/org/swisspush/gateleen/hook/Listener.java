package org.swisspush.gateleen.hook;

/**
 * Represents a listener.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class Listener {
    private String listenerId;
    private String listener;
    private String monitoredUrl;
    private Integer expireAfter;
    private HttpHook hook;

    /**
     * Creates a new instance of a HookListener.
     * 
     * @param listenerId - id of the listener eg. "http/colin/12345678".
     * @param monitoredUrl - URL segment before "/hooks/v1/listeners/",
     * eg. for "PUT http://bus.local/gps/v1/_hooks/listeners/http/colin/12345678"
     * it is "http://bus.local/gps/v1".
     * @param listener - URL segment after the monitoredUrl "http://bus.local/gps/v1"
     * and without the hook area.
     * eg. for "PUT http://bus.local/gps/v1/_hooks/listeners/http/colin/12345678"
     * it is "http/colin/12345678".
     * If it's a local listener, this can also be the target url.
     * @param hook - the hook of this listener
     */
    public Listener(String listenerId, String monitoredUrl, String listener, HttpHook hook) {
        this.listenerId = listenerId;
        this.monitoredUrl = monitoredUrl;
        this.listener = listener;
        this.setHook(hook);
    }

    /**
     * Returns the listener segment of the url.
     * 
     * @return String
     */
    public String getListener() {
        return listener;
    }

    /**
     * Sets the listener segment of the url.
     * 
     * @param listener listener
     */
    public void setListener(String listener) {
        this.listener = listener;
    }

    /**
     * Returns the url the listener is hooked up.
     * 
     * @return String
     */
    public String getMonitoredUrl() {
        return monitoredUrl;
    }

    /**
     * Sets the url the listener is hooked up.
     * 
     * @param monitoredUrl monitoredUrl
     */
    public void setMonitoredUrl(String monitoredUrl) {
        this.monitoredUrl = monitoredUrl;
    }

    /**
     * Returns the listener id (eg. http/colin/123)
     * 
     * @return id of the listener
     */
    public String getListenerId() {
        return listenerId;
    }

    /**
     * Sets the listener id (eg. http/colin/123).
     * 
     * @param listenerId listenerId
     */
    public void setListenerId(String listenerId) {
        this.listenerId = listenerId;
    }

    /**
     * Returns the expire after time, for the
     * request header.
     * Can be <code>null</code> if not set.
     * 
     * @return expire after time
     */
    public Integer getExpireAfter() {
        return expireAfter;
    }

    /**
     * Sets the expire after time, for the
     * request header.
     * Can be <code>null</code> if not set.
     * 
     * @param expireAfter expireAfter
     */
    public void setExpireAfter(Integer expireAfter) {
        this.expireAfter = expireAfter;
    }

    /**
     * Returns the hook of this listener.
     * 
     * @return the hook
     */
    public HttpHook getHook() {
        return hook;
    }

    /**
     * Sets the hook of this listener.
     * 
     * @param hook hook
     */
    public void setHook(HttpHook hook) {
        this.hook = hook;
    }
}
