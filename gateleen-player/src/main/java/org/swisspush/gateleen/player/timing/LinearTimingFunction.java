package org.swisspush.gateleen.player.timing;

import com.google.common.base.Function;

/**
 * Speeds up timings by a constant factor.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class LinearTimingFunction implements Function<Long, Long> {

    private double factor;

    public LinearTimingFunction(double factor) {
        this.factor = factor;
    }

    @Override
    public Long apply(Long millis) {
        return (long)(millis / factor);
    }
}
