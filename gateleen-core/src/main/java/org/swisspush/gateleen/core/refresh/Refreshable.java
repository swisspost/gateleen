/*
 * ------------------------------------------------------------------------------------------------
 * Copyright 2016 by Swiss Post, Information Technology Services
 * ------------------------------------------------------------------------------------------------
 * $Id$
 * ------------------------------------------------------------------------------------------------
 */
package org.swisspush.gateleen.core.refresh;

/**
 * Interface for classes which allows to be
 * refreshed (eg. refresh rules, schedulers, and so on).
 * 
 * @author ljucam
 */
public interface Refreshable {

    /**
     * Performs a refresh.
     */
    void refresh();
}
