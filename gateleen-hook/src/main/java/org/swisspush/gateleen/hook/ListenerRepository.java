package org.swisspush.gateleen.hook;

import java.util.List;

/**
 * A repository for listener hooks.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public interface ListenerRepository {

    /**
     * Adds a listener to the repository.
     * 
     * @param listener - the listener
     */
    void addListener(Listener listener);

    /**
     * Searches for listeners corresponding to the given url.
     * This is a hierarchical search, starting from top (/url/foo/bar) to bottom (/url).
     * 
     * @param url url
     * @return List with listeners for the url, can be empty if no listeners are found.
     */
    List<Listener> findListeners(String url);

    /**
     * Searches for listeners corresponding to the given url and the used http method.
     * This is a hierarchical search, starting from top (/url/foo/bar) to bottom (/url).
     * 
     * @param url url
     * @param method http method
     * @return List with listeners for the url, can be empty if no listeners are found.
     */
    List<Listener> findListeners(String url, String method);

    /**
     * Removes the listener for the given listenerId.
     * 
     * @param listenerId listenerId
     */
    void removeListener(String listenerId);

    /**
     * Returns the size of the repository.
     * If the repository is empty, it will return 0.
     * 
     * @return size
     */
    int size();

    /**
     * Returns whether the repository is empty
     * or not.
     * 
     * @return true if repository is empty.
     */
    boolean isEmpty();

    /**
     * Returns a copy of all registred listeners.
     * 
     * @return a copy of all registred listeners
     */
    List<Listener> getListeners();

}
