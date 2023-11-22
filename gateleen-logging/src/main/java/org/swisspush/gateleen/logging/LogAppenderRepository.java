package org.swisspush.gateleen.logging;

import org.apache.logging.log4j.core.Appender;

/**
 * A repository holding {@link Appender} instances. The repository allows to reuse an appender
 * instead of creating a new one for every log statement
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public interface LogAppenderRepository {

    boolean hasAppender(String name);

    void addAppender(String name, Appender appender);

    Appender getAppender(String name);

    void clearRepository();
}
