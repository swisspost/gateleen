package org.swisspush.gateleen.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

import java.util.*;

/**
 * Configurator used to configure a log4j repository dynamically.
 * @author schwabmar
 */
public class Log4jConfigurator {
    /**
     * The logger.
     */
    private static final Logger logging = LogManager.getLogger(Log4jConfigurator.class);

    /**
     * The root logger name.
     */
    private static final String ROOT = "root";

    /**
     * Reference to the one-and-only instance of this class.
     */
    private static Log4jConfigurator instance;

    private Log4jConfigurator() {
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
     * Retrieves all loggers from the log4j repository.
     *
     * @return List of <code>java.lang.String</code> containing all logger names.
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> getLoggers() {
        // Get all loggers
        List<String> list = new ArrayList<>();
        list.add(getLoggerName(LogManager.getRootLogger()));
        Collection<org.apache.logging.log4j.core.Logger> loggerCollection = ((LoggerContext) LogManager.getContext(false)).getLoggers();
        loggerCollection.forEach(logger -> {
            if (logger.getLevel() != null) {
                list.add(getLoggerName(logger));
            }
        });
        if (logging.isDebugEnabled()) {
            logging.debug("getLoggers() returns totally " + list.size() + " loggers");
        }
        return list;
    }

    /**
     * Retrieves all loggers from the log4j repository.
     *
     * @return Sorted list of <code>java.lang.String</code> containing all logger names.
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
     * @param logger A logger's name.
     * @return List of <code>java.lang.String</code> containing all appender names.
     * @throws IllegalArgumentException In case the logger isn't known.
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> getAppenders(String logger) throws IllegalArgumentException {

        Logger logobj = getLoggerFromName(logger);
        List<String> list = new ArrayList<>();
        Map<String, Appender> appenderMap =
                ((org.apache.logging.log4j.core.Logger) logobj).getAppenders();
        appenderMap.forEach((s, appender) -> list.add(appender.getName()));
        if (logging.isDebugEnabled()) {
            logging.debug("getAppenders(" + logger + ") found " + list.size() + " appenders");
        }
        return list;
    }

    /**
     * Gets a logger's actual level.
     *
     * @param logger The logger.
     * @return The logger's level or <code>null</code> if no level was directly set on the logger.
     * @throws IllegalArgumentException In case the logger isn't known.
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
     * @param logger The logger.
     * @return The logger's effective level.
     * @throws IllegalArgumentException In case the logger isn't known.
     */
    public synchronized Level getEffectiveLevel(String logger) throws IllegalArgumentException {
        Logger logobj = getLoggerFromName(logger);
        if (logging.isTraceEnabled()) {
            logging.trace("getEffectiveLevel(" + logger + ") returns " + logobj.getLevel());
        }
        return logobj.getLevel();
    }

    /**
     * Sets or deletes a logger's new level.
     *
     * @param name Name of the logger to set.
     * @param level  Level to set the logger to.
     * @throws IllegalArgumentException In case the logger isn't known.
     */
    public synchronized void setLoggerLevel(String name, String level) throws IllegalArgumentException {
        Configurator.setLevel(name, Level.toLevel(level));
        logging.info("New level for looger '" + name + "': " + level);
    }

    /**
     * Returns a logger's name. Hint: The special cases root logger ist handled correctly.
     *
     * @param logger The logger to examine.
     * @return The logger's name.
     */
    private String getLoggerName(Logger logger) {
        if (!(logger instanceof org.apache.logging.log4j.core.Logger)) {
            return null;
        }
        if (((org.apache.logging.log4j.core.Logger) logger).getParent() == null) {
            return ROOT;
        }
        return logger.getName();
    }

    /**
     * Resolves a logger name to the appropriate logger Hint: The special cases root logger ist handled correctly.
     *
     * @param name The logger's name
     * @return Logger
     * @throws IllegalArgumentException In case the logger isn't known.
     */
    private Logger getLoggerFromName(String name) throws IllegalArgumentException {
        Logger logger = null;
        if (name.equals(ROOT)) {
            logger = LogManager.getRootLogger();
        } else {
            logger = LogManager.getLogger(name);
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
         * @param l1 an <code>Object</code> value
         * @param l2 an <code>Object</code> value
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
         * @param o an <code>Object</code> value
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
