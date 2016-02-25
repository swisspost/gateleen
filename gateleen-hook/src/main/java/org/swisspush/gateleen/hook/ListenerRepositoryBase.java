package org.swisspush.gateleen.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Abstrac base class for all ListenerRepositires.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public abstract class ListenerRepositoryBase<T> implements ListenerRepository {

    /**
     * Searches for all listeners which monitored url matches
     * the given url.
     * 
     * @see {@link #containsKey(Object, String)}
     * @see {@link #get(Object, String)}
     * @param urlToListenerContainer container
     * @param url
     * @return List
     */
    List<Listener> findListeners(T urlToListenerContainer, String url) {
        List<Listener> foundListener = new ArrayList<Listener>();

        // get rid of url parameters (if there are any)
        String tUrl = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;

        int index = -1;
        while ((index = tUrl.lastIndexOf('/')) > -1) {

            // found
            if (containsKey(urlToListenerContainer, tUrl)) {
                foundListener.addAll(get(urlToListenerContainer, tUrl));
            }

            tUrl = tUrl.substring(0, index);
        }

        return foundListener;
    }

    /**
     * Checks if the container contains the key.
     * Returns only true if the key is found.
     * This method allows you to create your own implementation
     * depending on your needs.
     * 
     * @see {@link #findListeners(Object, String)}
     * @param container container
     * @param key key
     * @return true if key is found.
     */
    abstract boolean containsKey(T container, String key);

    /**
     * Returns a set with listeners monitoring the given url.
     * Please implement this method according to your needs.
     * 
     * @param container container
     * @param url - a monitored url
     * @return set with listeners for the given url
     */
    public abstract Set<Listener> get(T container, String url);
}
