package org.swisspush.gateleen.core.util;

import io.vertx.core.Vertx;
import org.swisspush.gateleen.core.util.SlicedLoop.*;

import java.util.Iterator;

import static org.swisspush.gateleen.core.util.SlicedLoop.*;

/**
 * Let users produce {@link SlicedLoop} instances without they need to bother about dependencies.
 */
public class SlicedLoopFactory {

    private final Vertx vertx;
    private final DeferredReactorEnqueue deferredReactorEnqueue;

    public SlicedLoopFactory(Vertx vertx, DeferredReactorEnqueue deferredReactorEnqueue) {
        this.vertx = vertx;
        this.deferredReactorEnqueue = deferredReactorEnqueue;
    }

    public <T> SlicedLoop<T> slicedLoop(Iterator<T> src, Destination<T> dst) {
        return slicedLoop(DEFAULT_DBG_HINT, src, dst);
    }

    public <T> SlicedLoop<T> slicedLoop(String debugHint, Iterator<T> src, Destination<T> dst) {
        return slicedLoop(DEFAULT_SLICE_THRESHOLD_NS, debugHint, src, dst);
    }

    public <T> SlicedLoop<T> slicedLoop(int sliceThresholdNs, String debugHint, Iterator<T> src, Destination<T> dst) {
        return slicedLoop(sliceThresholdNs, DEFAULT_YELLING_THRESHOLD_NS, debugHint, src, dst);
    }

    public <T> SlicedLoop<T> slicedLoop(int sliceThresholdNs, int yellingThresholdNs, String debugHint, Iterator<T> src, Destination<T> dst) {
        return new SlicedLoop<>(vertx, deferredReactorEnqueue, sliceThresholdNs, yellingThresholdNs, debugHint, src, dst);
    }

}
