package org.swisspush.gateleen.hook;

import io.vertx.core.MultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.util.HttpHeaderUtil;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Local in-memory implementation of a LocalListenerRepository.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class LocalListenerRepository extends ListenerRepositoryBase<Map<String, Set<Listener>>>implements ListenerRepository {
    private Logger log = LoggerFactory.getLogger(LocalListenerRepository.class);

    /*
     * For search purposes we have two maps.
     * Both contain listeners.
     * The url map contains all listeners
     * directly monitoring this url.
     * The listener map contains all listeners.
     */
    private Map<String, Set<Listener>> urlToListenersMap;
    private Map<String, Listener> listenerToUrlMap;

    /**
     * Creates a new instance of the local in-memory LocalHookListenerRepository.
     */
    public LocalListenerRepository() {
        urlToListenersMap = new HashMap<>();
        listenerToUrlMap = new HashMap<>();
    }

    @Override
    public void addListener(Listener listener) {
        /*
         * if this is an update for a listener, we
         * need to remove the old listener
         */
        removeListener(listener.getListenerId());

        log.debug("Add listener {} for resource {} with id {}", listener.getListener(), listener.getMonitoredUrl(),
                listener.getListenerId());

        /*
         * do we have already a set of listeners
         * for this url?
         */
        Set<Listener> listeners = urlToListenersMap.get(listener.getMonitoredUrl());
        if (listeners == null) {
            listeners = new HashSet<>();
        }

        listeners.add(listener);

        urlToListenersMap.put(listener.getMonitoredUrl(), listeners);
        listenerToUrlMap.put(listener.getListenerId(), listener);
    }

    @Override
    public List<Listener> findListeners(String url) {
        return findListeners(urlToListenersMap, url);
    }

    @Override
    public void removeListener(String listenerId) {
        log.debug("Remove listener for id {}", listenerId);

        Listener listenerToRemove = listenerToUrlMap.get(listenerId);

        if (listenerToRemove != null) {
            listenerToUrlMap.remove(listenerId);

            Set<Listener> listeners = urlToListenersMap.get(listenerToRemove.getMonitoredUrl());
            listeners.remove(listenerToRemove);

            if (listeners.isEmpty()) {
                urlToListenersMap.remove(listenerToRemove.getMonitoredUrl());
            }
        }
    }

    @Override
    public int size() {
        return listenerToUrlMap.size();
    }

    @Override
    public boolean isEmpty() {
        return listenerToUrlMap.isEmpty();
    }

    @Override
    public Set<Listener> get(Map<String, Set<Listener>> container, String key) {
        return container.get(key);
    }

    @Override
    boolean containsKey(Map<String, Set<Listener>> container, String key) {
        return container.containsKey(key);
    }

    @Override
    public List<Listener> getListeners() {
        return new ArrayList<>(listenerToUrlMap.values());
    }

    @Override
    public List<Listener> findListeners(String url, String method, MultiMap headers) {
        List<Listener> result = new ArrayList<>();

        for (Listener listener : findListeners(url)) {
            if (doesMethodMatch(listener, method) && doHeadersMatch(listener, headers)) {
                result.add(listener);
            }
        }

        return result;
    }

    private boolean doesMethodMatch(Listener listener, String method) {
        return listener.getHook().getMethods().isEmpty() || listener.getHook().getMethods().contains(method);
    }

    private boolean doHeadersMatch(Listener listener, MultiMap headers) {
        Pattern headersFilterPattern = listener.getHook().getHeadersFilterPattern();
        if (headersFilterPattern != null) {
            return HttpHeaderUtil.hasMatchingHeader(headers, headersFilterPattern);
        }
        return true;
    }
}
