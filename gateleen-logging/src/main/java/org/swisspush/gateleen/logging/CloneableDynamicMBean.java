package org.swisspush.gateleen.logging;

import javax.management.DynamicMBean;

/**
 * Interface of a clonable, dynamic MBean.
 *
 * @author schwabmar
 */
public interface CloneableDynamicMBean extends DynamicMBean, Cloneable {
    /**
     * Makes a clone of the MBean.
     *
     * @return The MBean's clone.
     */
    Object clone() throws CloneNotSupportedException;
}
