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
        this.hook = hook;
    }

    /**
     * Returns the listener ID.
     *
     * @return The listener ID.
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
     * Returns the monitored URL.
     *
     * @return The monitored URL.
     */
    public String getMonitoredUrl() {
        return monitoredUrl;
    }

    /**
     * Sets the monitored URL.
     *
     * @param monitoredUrl The monitored URL.
     */
    public void setMonitoredUrl(String monitoredUrl) {
        this.monitoredUrl = monitoredUrl;
    }

    /**
     * Returns the listener URL segment.
     *
     * @return The listener URL segment.
     */
    public String getListener() {
        return listener;
    }

    /**
     * Sets the listener URL segment.
     *
     * @param listener The listener URL segment.
     */
    public void setListener(String listener) {
        this.listener = listener;
    }

    /**
     * Returns the expiration time.
     *
     * @return The expiration time, or null if not set.
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
