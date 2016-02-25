package org.swisspush.gateleen.player.log;

/**
 * Interface for an active element providing a request log.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public interface Collector {

    BufferingRequestLog getRequestLog();

    void start();

    void stop();
}
