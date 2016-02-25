package org.swisspush.gateleen.core.http;

import org.slf4j.Logger;
import org.slf4j.Marker;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class RequestLoggerWrapper implements Logger {

    private Logger logger;
    private String requestId;

    public RequestLoggerWrapper(Logger logger, String requestId) {
        this.logger = logger;
        this.requestId = requestId;
    }

    public boolean isTraceEnabled() {
        return logger.isTraceEnabled();
    }

    public boolean isTraceEnabled(Marker marker) {
        return logger.isTraceEnabled(marker);
    }

    public void trace(String msg) {
        if (!logger.isTraceEnabled()) {
            return;
        }
        logger.trace(requestId + " " + msg);
    }

    public void trace(String format, Object arg) {
        if (!logger.isTraceEnabled()) {
            return;
        }
        logger.trace(requestId + " " + format, arg);
    }

    public void trace(String format, Object arg1, Object arg2) {
        if (!logger.isTraceEnabled()) {
            return;
        }
        logger.trace(requestId + " " + format, arg1, arg2);

    }

    public void trace(String format, Object argArray[]) {
        if (!logger.isTraceEnabled()) {
            return;
        }
        logger.trace(requestId + " " + format, argArray);

    }

    public void trace(String msg, Throwable t) {
        if (!logger.isTraceEnabled()) {
            return;
        }
        logger.trace(requestId + " " + msg, t);
    }

    public void trace(Marker marker, String msg) {
        if (!logger.isTraceEnabled(marker)) {
            return;
        }
        logger.trace(marker, requestId + " " + msg);
    }

    public void trace(Marker marker, String format, Object arg) {
        if (!logger.isTraceEnabled(marker)) {
            return;
        }
        logger.trace(marker, requestId + " " + format, arg);

    }

    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        if (!logger.isTraceEnabled(marker)) {
            return;
        }
        logger.trace(marker, requestId + " " + format, arg1, arg2);

    }

    public void trace(Marker marker, String format, Object argArray[]) {
        if (!logger.isTraceEnabled(marker)) {
            return;
        }
        logger.trace(marker, requestId + " " + format, argArray);

    }

    public void trace(Marker marker, String msg, Throwable t) {
        if (!logger.isTraceEnabled(marker)) {
            return;
        }
        logger.trace(marker, requestId + " " + msg, t);
    }

    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    public boolean isDebugEnabled(Marker marker) {
        return logger.isDebugEnabled(marker);
    }

    public void debug(String msg) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug(requestId + " " + msg);
    }

    public void debug(String format, Object arg) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug(requestId + " " + format, arg);

    }

    public void debug(String format, Object arg1, Object arg2) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug(requestId + " " + format, arg1, arg2);

    }

    public void debug(String format, Object argArray[]) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug(requestId + " " + format, argArray);

    }

    public void debug(String msg, Throwable t) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug(requestId + " " + msg, t);
    }

    public void debug(Marker marker, String msg) {
        if (!logger.isDebugEnabled(marker)) {
            return;
        }
        logger.debug(marker, requestId + " " + msg);
    }

    public void debug(Marker marker, String format, Object arg) {
        if (!logger.isDebugEnabled(marker)) {
            return;
        }
        logger.debug(marker, requestId + " " + format, arg);

    }

    public void debug(Marker marker, String format, Object arg1, Object arg2) {
        if (!logger.isDebugEnabled(marker)) {
            return;
        }
        logger.debug(marker, requestId + " " + format, arg1, arg2);

    }

    public void debug(Marker marker, String format, Object argArray[]) {
        if (!logger.isDebugEnabled(marker)) {
            return;
        }
        logger.debug(marker, requestId + " " + format, argArray);

    }

    public void debug(Marker marker, String msg, Throwable t) {
        if (!logger.isDebugEnabled(marker)) {
            return;
        }
        logger.debug(marker, requestId + " " + msg, t);
    }

    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }

    public boolean isInfoEnabled(Marker marker) {
        return logger.isInfoEnabled(marker);
    }

    public void info(String msg) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        logger.info(requestId + " " + msg);
    }

    public void info(String format, Object arg) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        logger.info(requestId + " " + format, arg);

    }

    public void info(String format, Object arg1, Object arg2) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        logger.info(requestId + " " + format, arg1, arg2);

    }

    public void info(String format, Object argArray[]) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        logger.info(requestId + " " + format, argArray);

    }

    public void info(String msg, Throwable t) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        logger.info(requestId + " " + msg, t);
    }

    public void info(Marker marker, String msg) {
        if (!logger.isInfoEnabled(marker)) {
            return;
        }
        logger.info(marker, requestId + " " + msg);
    }

    public void info(Marker marker, String format, Object arg) {
        if (!logger.isInfoEnabled(marker)) {
            return;
        }
        logger.info(marker, requestId + " " + format, arg);

    }

    public void info(Marker marker, String format, Object arg1, Object arg2) {
        if (!logger.isInfoEnabled(marker)) {
            return;
        }
        logger.info(marker, requestId + " " + format, arg1, arg2);

    }

    public void info(Marker marker, String format, Object argArray[]) {
        if (!logger.isInfoEnabled(marker)) {
            return;
        }
        logger.info(marker, requestId + " " + format, argArray);

    }

    public void info(Marker marker, String msg, Throwable t) {
        if (!logger.isInfoEnabled(marker)) {
            return;
        }
        logger.info(marker, requestId + " " + msg, t);
    }

    public boolean isWarnEnabled() {
        return logger.isWarnEnabled();
    }

    public boolean isWarnEnabled(Marker marker) {
        return logger.isWarnEnabled(marker);
    }

    public void warn(String msg) {
        if (!logger.isWarnEnabled()) {
            return;
        }
        logger.warn(requestId + " " + msg);
    }

    public void warn(String format, Object arg) {
        if (!logger.isWarnEnabled()) {
            return;
        }
        logger.warn(requestId + " " + format, arg);

    }

    public void warn(String format, Object arg1, Object arg2) {
        if (!logger.isWarnEnabled()) {
            return;
        }
        logger.warn(requestId + " " + format, arg1, arg2);

    }

    public void warn(String format, Object argArray[]) {
        if (!logger.isWarnEnabled()) {
            return;
        }
        logger.warn(requestId + " " + format, argArray);

    }

    public void warn(String msg, Throwable t) {
        if (!logger.isWarnEnabled()) {
            return;
        }
        logger.warn(requestId + " " + msg, t);
    }

    public void warn(Marker marker, String msg) {
        if (!logger.isWarnEnabled(marker)) {
            return;
        }
        logger.warn(marker, requestId + " " + msg);
    }

    public void warn(Marker marker, String format, Object arg) {
        if (!logger.isWarnEnabled(marker)) {
            return;
        }
        logger.warn(marker, requestId + " " + format, arg);

    }

    public void warn(Marker marker, String format, Object arg1, Object arg2) {
        if (!logger.isWarnEnabled(marker)) {
            return;
        }
        logger.warn(marker, requestId + " " + format, arg1, arg2);

    }

    public void warn(Marker marker, String format, Object argArray[]) {
        if (!logger.isWarnEnabled(marker)) {
            return;
        }
        logger.warn(marker, requestId + " " + format, argArray);

    }

    public void warn(Marker marker, String msg, Throwable t) {
        if (!logger.isWarnEnabled(marker)) {
            return;
        }
        logger.warn(marker, requestId + " " + msg, t);
    }

    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    public boolean isErrorEnabled(Marker marker) {
        return logger.isErrorEnabled(marker);
    }

    public void error(String msg) {
        if (!logger.isErrorEnabled()) {
            return;
        }
        logger.error(requestId + " " + msg);
    }

    public void error(String format, Object arg) {
        if (!logger.isErrorEnabled()) {
            return;
        }
        logger.error(requestId + " " + format, arg);

    }

    public void error(String format, Object arg1, Object arg2) {
        if (!logger.isErrorEnabled()) {
            return;
        }
        logger.error(requestId + " " + format, arg1, arg2);

    }

    public void error(String format, Object argArray[]) {
        if (!logger.isErrorEnabled()) {
            return;
        }
        logger.error(requestId + " " + format, argArray);

    }

    public void error(String msg, Throwable t) {
        if (!logger.isErrorEnabled()) {
            return;
        }
        logger.error(requestId + " " + msg, t);
    }

    public void error(Marker marker, String msg) {
        if (!logger.isErrorEnabled(marker)) {
            return;
        }
        logger.error(marker, requestId + " " + msg);
    }

    public void error(Marker marker, String format, Object arg) {
        if (!logger.isErrorEnabled(marker)) {
            return;
        }
        logger.error(marker, requestId + " " + format, arg);

    }

    public void error(Marker marker, String format, Object arg1, Object arg2) {
        if (!logger.isErrorEnabled(marker)) {
            return;
        }
        logger.error(marker, requestId + " " + format, arg1, arg2);

    }

    public void error(Marker marker, String format, Object argArray[]) {
        if (!logger.isErrorEnabled(marker)) {
            return;
        }
        logger.error(marker, requestId + " " + format, argArray);

    }

    public void error(Marker marker, String msg, Throwable t) {
        if (!logger.isErrorEnabled(marker)) {
            return;
        }
        logger.error(marker, requestId + " " + msg, t);
    }

    public String getName() {
        return logger.getName();
    }

}
