package org.swisspush.gateleen.logging;

import org.apache.log4j.Appender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * Configurator used to configure a log4j repository dynamically. Wheter all known loggers or only the explicitly
 * configured loggers are configured is defined by the system property "org.swisspush.gateleen.logging.log4j.showall" which
 * defaults to false. Thus per default only the loggers are modified that have been explicitly configured to the log4j
 * system (typically via a log4j configuration file such as log4j.properties).
 *
 * @author schwabmar
 * @see #SYS_PROP_SHOW_ALL
 */
public class Log4jConfigurator {
    /** The logger. */
    private static final Logger logging = Logger.getLogger(Log4jConfigurator.class);

    /** The root logger name. */
    private static final String ROOT = "root";

    /** Reference to the one-and-only instance of this class. */
    private static Log4jConfigurator instance;

    /** Name of the "show all loggers" system property. */
    public static final String SYS_PROP_SHOW_ALL = "org.swisspush.gateleen.logging.log4j.showall";

    /**
     * Constructor of the object. This constructors retrieves the value of the showAll flag from the showall system
     * property.
     *
     * @see #SYS_PROP_SHOW_ALL
     */
    private Log4jConfigurator() {
        // Check if all loggers have to be shown (via system property)
        super();
    }

    /**
     * Gets the one-and-only instance of the class <code>Log4jConfigurator</code>.
     *
     * @return Instance.
     */
    public static synchronized Log4jConfigurator getInstance() {
        if (instance == null) {
            logging.trace("Creating new Log4jConfigurator instance");
            instance = new Log4jConfigurator();
        }
        return instance;
    }

    /**
     * Retrieves all loggers from the log4j repository. Dependent on the flag showAll, this method either returns only
     * the direct configured loggers or all known loggers.
     *
     * @return List of <code>java.lang.String</code> containing all logger names.
     * @see #SYS_PROP_SHOW_ALL
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> getLoggers() {
        // Get all logger
        List<String> list = new ArrayList<>();
        list.add(getLoggerName(LogManager.getRootLogger()));
        Enumeration<Logger> enumer = LogManager.getCurrentLoggers();
        while (enumer.hasMoreElements()) {
            Logger logger = enumer.nextElement();
            if (logger.getLevel() != null) {
                list.add(getLoggerName(logger));
            }
        }
        if (logging.isDebugEnabled()) {
            logging.debug("getLoggers() returns totally " + list.size() + " loggers");
        }
        return list;
    }

    /**
     * Retrieves all loggers from the log4j repository. Dependent on the flag showAll, this method either returns only
     * the direct configured loggers or all known loggers.
     *
     * @return Sorted list of <code>java.lang.String</code> containing all logger names.
     * @see #SYS_PROP_SHOW_ALL
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> getLoggersSorted() {
        List<String> loggers = getLoggers();
        @SuppressWarnings("rawtypes")
        Comparator comp = new LoggerComparator();
        loggers.sort(comp);
        return loggers;
    }

    /**
     * Gets the appenders to a specific logger.
     *
     * @param logger
     *            A logger's name.
     * @return List of <code>java.lang.String</code> containing all appender names.
     * @exception IllegalArgumentException
     *                In case the logger isn't known.
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> getAppenders(String logger) throws IllegalArgumentException {
        Logger logobj = getLoggerFromName(logger);
        List<String> list = new ArrayList<>();
        Enumeration<Appender> e = logobj.getAllAppenders();
        while (e.hasMoreElements()) {
            list.add(e.nextElement().getName());
        }
        if (logging.isDebugEnabled()) {
            logging.debug("getAppenders(" + logger + ") found " + list.size() + " appenders");
        }
        return list;
    }

    /**
     * Gets a logger's actual level.
     *
     * @param logger
     *            The logger.
     * @return The logger's level or <code>null</code> if no level was directly set on the logger.
     * @exception IllegalArgumentException
     *                In case the logger isn't known.
     */
    public synchronized Level getLevel(String logger) throws IllegalArgumentException {
        Logger logobj = getLoggerFromName(logger);
        if (logging.isTraceEnabled()) {
            logging.trace("getLevel(" + logger + ") returns " + logobj.getLevel());
        }
        return logobj.getLevel();
    }

    /**
     * Gets a logger's effective level thus either the direct level or the inherited level from it's parent logger.
     *
     * @param logger
     *            The logger.
     * @return The logger's effective level.
     * @exception IllegalArgumentException
     *                In case the logger isn't known.
     */
    public synchronized Level getEffectiveLevel(String logger) throws IllegalArgumentException {
        Logger logobj = getLoggerFromName(logger);
        if (logging.isTraceEnabled()) {
            logging.trace("getEffectiveLevel(" + logger + ") returns " + logobj.getEffectiveLevel());
        }
        return logobj.getEffectiveLevel();
    }

    /**
     * Sets or deletes a logger's new level.
     *
     * @param logger
     *            Name of the logger to set.
     * @param level
     *            Level to set the logger to.
     * @exception IllegalArgumentException
     *                In case the logger isn't known.
     */
    public synchronized void setLoggerLevel(String logger, String level) throws IllegalArgumentException {
        Logger logobj = getLoggerFromName(logger);
        logobj.setLevel(Level.toLevel(level));
        logging.info("New level for looger '" + logger + "': " + level);
    }

    /**
     * Returns a logger's name. Hint: The special cases root logger ist handled correctly.
     *
     * @param logger
     *            The logger to examine.
     * @return The logger's name.
     */
    private String getLoggerName(Logger logger) {
        if (logger == null) {
            return null;
        }
        if (logger.getParent() == null) {
            return ROOT;
        }
        return logger.getName();
    }

    /**
     * Resolves a logger name to the appropriate logger Hint: The special cases root logger ist handled correctly.
     *
     * @param name
     *            The logger's name
     * @return Logger
     * @exception IllegalArgumentException
     *                In case the logger isn't known.
     */
    private Logger getLoggerFromName(String name) throws IllegalArgumentException {
        Logger logger = null;
        if (name.equals(ROOT)) {
            logger = Logger.getRootLogger();
        } else {
            logger = Logger.getLogger(name);
        }
        if (logger == null) {
            throw new IllegalArgumentException("Unknown logger 'null'");
        }
        return logger;
    }

    /**
     * Compare the names of two <code>Logger</code>s. Used for sorting.
     */
    private class LoggerComparator implements Comparator<String> {
        /**
         * Compare the names of two <code>Logger</code>s.
         *
         * @param l1
         *            an <code>Object</code> value
         * @param l2
         *            an <code>Object</code> value
         * @return an <code>int</code> value
         */
        public int compare(String l1, String l2) {
            // Check for null values
            if (l1 == null) {
                l1 = "";
            }
            if (l2 == null) {
                l2 = "";
            }
            // The root logger is first in hierarchy
            if (l1.equals(ROOT)) {
                if (l2.equals(ROOT)) {
                    return 0;
                }
                return -1;
            }
            if (l2.equals(ROOT)) {
                return 1;
            }
            // No root loggers involved - simple string compare
            return l1.compareTo(l2);
        }

        /**
         * Return <code>true</code> if the <code>Object</code> is a <code>LoggerComparator</code> instance.
         *
         * @param o
         *            an <code>Object</code> value
         * @return a <code>boolean</code> value
         */
        public boolean equals(Object o) {
            return (o instanceof LoggerComparator);
        }

        /**
         * Returns the parent's hashcode.
         *
         * @return int The hashcode value
         */
        public int hashCode() {
            return super.hashCode();
        }
    }
}
