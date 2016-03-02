package org.swisspush.gateleen.logging.logging;

import org.apache.log4j.Logger;

import javax.management.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * MBean used to dynamically configure log4j. Simply all known and named loggers - including the root logger - are
 * exposed as attributes of this MBean. The attribute's values represent the current level of the appropriate logger.
 * Hint: This component itself uses log4j as the logging framework.
 *
 * @see Log4jConfigurator
 * @author schwabmar
 */
public class Log4jConfiguratorMBean implements CloneableDynamicMBean {
    /** The logger (this logger won't be cloned!). */
    private final Logger logging = Logger.getLogger(Log4jConfiguratorMBean.class);

    /** Asterisk used as trailing string for inherited logging levels. */
    public static final String ASTERISK = "*";

    /** Default name. This is the value of the name part of this MBean's JMX object name. */
    public static final String DEFAULT_JMX_NAME = "Log4jConfigurator";

    /** Name of the method setLoggerLevel. */
    private static final String SET_LOGGER_LEVEL_METHOD = "setLoggerLevel";

    /**
     * Default constructor for Log4jConfiguratorMBean.
     */
    public Log4jConfiguratorMBean() {
        super();
    }

    /**
     * Gets the value of an attribute and thus the level of a certain logger. If the logger hasn't got an assigned
     * level, the level from the parent logger trailed with an asterisk is returned.
     *
     * @see DynamicMBean#getAttribute(String)
     */
    public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
        // Get a logger's level
        logging.trace("getAttribute(" + attribute + ")");
        Log4jConfigurator cnf = Log4jConfigurator.getInstance();
        String level = null;
        try {
            if (cnf.getLevel(attribute) == null) {
                level = cnf.getEffectiveLevel(attribute).toString();
                level += ASTERISK;
            } else {
                level = cnf.getLevel(attribute).toString();
            }
        } catch (IllegalArgumentException e) {
            String msg = "Unknown attribute '" + attribute + "'";
            logging.warn(msg, e);
            throw new AttributeNotFoundException(msg);
        }
        return level;
    }

    /**
     * Sets the value of an attribute.
     *
     * @see DynamicMBean#setAttribute(Attribute)
     */
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException,
            MBeanException, ReflectionException {
        // Set a logger's new level
        logging.trace("setAttribute(" + attribute + ")");
        Log4jConfigurator cnf = Log4jConfigurator.getInstance();
        try {
            cnf.setLoggerLevel(attribute.getName(), (String) attribute.getValue());
        } catch (IllegalArgumentException e) {
            String msg = "Unknown attribute '" + attribute + "'";
            logging.warn(msg, e);
            throw new AttributeNotFoundException(msg);
        } catch (ClassCastException e) {
            String msg = "Unexpected type of attribute value; expected " + String.class.getName() + " but got "
                    + attribute.getValue().getClass().getName();
            logging.warn(msg, e);
            throw new MBeanException(e, msg);
        }
    }

    /**
     * Gets the values of several attributes. Delegates the work to the method <code>getAttribute</code>.
     *
     * @see #getAttribute(String)
     * @see DynamicMBean#getAttributes(String[])
     */
    public AttributeList getAttributes(String[] attributes) {
        logging.trace("getAttributes(" + attributes + ")");
        AttributeList ret = new AttributeList();
        for (int i = 0; i < attributes.length; ++i) {
            Object val = null;
            try {
                val = getAttribute(attributes[i]);
            } catch (Exception e) {
                logging.error(e);
            }
            ret.add(new Attribute(attributes[i], val));
        }
        return ret;
    }

    /**
     * Sets the values of several attributes. Delegates the work to the method <code>setAttribute</code>.
     *
     * @see #setAttribute(Attribute)
     * @see DynamicMBean#setAttributes(AttributeList)
     */
    public AttributeList setAttributes(AttributeList attributes) {
        logging.trace("setAttributes(" + attributes + ")");
        @SuppressWarnings("rawtypes")
        Iterator it = attributes.iterator();
        while (it.hasNext()) {
            try {
                setAttribute((Attribute) it.next());
            } catch (Exception e) {
                logging.error(e);
            }
        }
        return null;
    }

    /**
     * Invokes an operation on the MBean. This method is generically implemented - it simply tries to call a method
     * named &lt;actionName&gt; with the provided parameters.
     *
     * @see DynamicMBean#invoke(String, Object[], String[])
     */
    public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException,
            ReflectionException {
        logging.trace("invoke(" + actionName + "," + params + "," + signature + ")");
        // Get the method to invoke
        Method action = null;
        try {
            // Get the signature as class array (not string array)
            @SuppressWarnings("rawtypes")
            Class[] classes = new Class[signature.length];
            for (int idx = 0; idx < signature.length; ++idx) {
                classes[idx] = Class.forName(signature[idx]);
            }
            // Get the method that handles this invocation
            action = this.getClass().getMethod(actionName, classes);
        } catch (Exception e) {
            String msg = "Unknown action " + actionName;
            logging.error(msg, e);
            throw new ReflectionException(e, msg);
        }
        // Invoke the method
        try {
            Object ret = action.invoke(this, params);
            logging.info("Successfully invoked action " + actionName);
            return ret;
        } catch (Exception e) {
            String msg = "Error while invoking action " + actionName;
            logging.error(msg, e);
            throw new ReflectionException(e, msg);
        }
    }

    /**
     * Returns all informations on the MBean, such as attributes and operations.
     *
     * @see #getAttributeInfo()
     * @see #getConstructorInfo()
     * @see #getOperationInfo()
     * @see #getNotificationInfo()
     * @see DynamicMBean#getMBeanInfo()
     */
    public MBeanInfo getMBeanInfo() {
        logging.trace("getMBeanInfo()");
        MBeanInfo mbeanInfo = new MBeanInfo(Log4jConfiguratorMBean.class.getName(), "Test implementation of a MBean",
                getAttributeInfo(), getConstructorInfo(), getOperationInfo(), getNotificationInfo());
        return mbeanInfo;
    }

    /**
     * Gets informations on all attributes exposed by the MBean.
     *
     * @return Attribute information.
     */
    private MBeanAttributeInfo[] getAttributeInfo() {
        // Get all loggers
        Log4jConfigurator cnf = Log4jConfigurator.getInstance();
        List<String> loggers;
        loggers = cnf.getLoggersSorted();
        MBeanAttributeInfo[] attrInfo = new MBeanAttributeInfo[loggers.size()];
        Iterator<String> it = loggers.iterator();
        for (int idx = 0; it.hasNext(); ++idx) {
            String logger = it.next();
            attrInfo[idx] = new MBeanAttributeInfo(logger, String.class.getName(), "Level for log4j logger '" + logger
                    + "'", true, true, false);
        }
        // Debug logging message
        if (logging.isDebugEnabled()) {
            for (int idx = 0; idx < attrInfo.length; ++idx) {
                logging.debug("Attribute '" + attrInfo[idx].getName() + "': type=" + attrInfo[idx].getType()
                        + "; readable=" + attrInfo[idx].isReadable() + "; writable=" + attrInfo[idx].isWritable()
                        + "; is=" + attrInfo[idx].isIs());
            }
        }
        return attrInfo;
    }

    /**
     * Gets informations on all constructors used to create the MBean.
     *
     * @return Constructor information.
     */
    private MBeanConstructorInfo[] getConstructorInfo() {
        @SuppressWarnings("rawtypes")
        Constructor[] ctors = Log4jConfiguratorMBean.class.getConstructors();
        MBeanConstructorInfo[] ctorInfo = new MBeanConstructorInfo[ctors.length];
        try {
            for (int idx = 0; idx < ctors.length; ++idx) {
                ctorInfo[idx] = new MBeanConstructorInfo("Default c'tor",
                        Log4jConfiguratorMBean.class.getConstructor(new Class[0]));
            }
        } catch (NoSuchMethodException e) {
            logging.error(e);
        }
        return ctorInfo;
    }

    /**
     * Gets informations on all operations exposed by the MBean.
     *
     * @return Operation information.
     */
    private MBeanOperationInfo[] getOperationInfo() {
        // Expose the operation used to set log levels on any logger
        try {
            String description = "Sets the level for one ore more loggers.";
            MBeanParameterInfo p1 = new MBeanParameterInfo("NamePattern", String.class.getName(),
                    "Regexp used to select the loggers, e.g. ^org.swisspush.*");
            MBeanParameterInfo p2 = new MBeanParameterInfo("Level", String.class.getName(),
                    "New level (one of trace, debug, info, warn, error, fatal)");
            MBeanParameterInfo[] signature = { p1, p2 };
            String returnType = String.class.getName();
            MBeanOperationInfo anySetOp = new MBeanOperationInfo(SET_LOGGER_LEVEL_METHOD, description, signature,
                    returnType, MBeanOperationInfo.ACTION);
            return new MBeanOperationInfo[] { anySetOp };
        } catch (Exception e) {
            logging.error("Unable to expose method " + SET_LOGGER_LEVEL_METHOD, e);
        }
        return null;
    }

    /**
     * Gets informations on all notifications the MBean is interested in.
     *
     * @return Notification information.
     */
    private MBeanNotificationInfo[] getNotificationInfo() {
        return null;
    }

    /**
     * Directly sets a level on one ore more loggers. Therefore the loggers to modify are selected via a pattern - all
     * loggers with a name matching the provided pattern are set to the new level.<br/>
     * Example: ^org\.swisspush\..* matches all names beginning with org.swisspush (this is the exact expression - in almost
     * every case this expression can be simplified to ^org.swisspush.*). <br>
     * This method is only invoked by the <code>invoke</code> method of this class and has to be public (since it is
     * called via reflection API).
     *
     * @param pattern
     *            Regular expression used to select the loggers.
     * @param level
     *            New level.
     * @return The total number of modified loggers.
     * @see org.apache.log4j.Level
     * @see #invoke(String, Object[], String[])
     * @see #getOperationInfo()
     */
    public String setLoggerLevel(String pattern, String level) {
        int modcount = 0;
        try {
            // Set the level on all loggers with a matching name
            Pattern rep = Pattern.compile(pattern);
            Log4jConfigurator cnf = Log4jConfigurator.getInstance();
            List<String> loggers;
            loggers = cnf.getLoggers();
            for (Iterator<String> it = loggers.iterator(); it.hasNext();) {
                String logger = it.next();
                if (rep.matcher(logger).matches()) {
                    cnf.setLoggerLevel(logger, level);
                    modcount++;
                }
            }
        } catch (PatternSyntaxException e) {
            logging.error("Invalid logger name pattern " + pattern, e);
        }
        String msg;
        if (modcount == 0) {
            msg = "No logger matched the pattern";
        } else if (modcount == 1) {
            msg = "1 logger was modified";
        } else {
            msg = modcount + " loggers were modified";
        }
        return msg;
    }

    /**
     * Creates a clone of the object.
     */
    @Override
    public Object clone() throws CloneNotSupportedException {
        // Nothing to do here - m_log isn't cloned, all other fields are static or primitive
        return super.clone();
    }
}
