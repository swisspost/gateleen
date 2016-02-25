package org.swisspush.gateleen.player.timing;

/**
 * Converts real time intervals into simulated time intervals.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public interface TimingFunction {

    long convertInterval(long millis);
}
