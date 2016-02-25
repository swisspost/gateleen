package org.swisspush.gateleen.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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
        urlToListenersMap = new HashMap<String, Set<Listener>>();
        listenerToUrlMap = new HashMap<String, Listener>();
    }

    @Override
    public void addListener(Listener listener) {
        /*
         * if this is an update for a listener, we
         * need to remove the old listener
         */
        removeListener(listener.getListenerId());

        log.debug("Add listener " + listener.getListener() + " for resource " + listener.getMonitoredUrl() + " with id " + listener.getListenerId());

        /*
         * do we have already a set of listeners
         * for this url?
         */
        Set<Listener> listeners = urlToListenersMap.get(listener.getMonitoredUrl());
        if (listeners == null) {
            listeners = new HashSet<Listener>();
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
        log.debug("Remove listener for id " + listenerId);

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
        return new ArrayList<Listener>(listenerToUrlMap.values());
    }

    @Override
    public List<Listener> findListeners(String url, String method) {
        List<Listener> result = new ArrayList<>();

        for (Listener listener : findListeners(url)) {
            if (listener.getHook().getMethods().isEmpty() || listener.getHook().getMethods().contains(method)) {
                result.add(listener);
            }
        }

        return result;
    }
}
