package org.swisspush.gateleen.expansion;

import java.util.Iterator;
import org.swisspush.gateleen.expansion.SlicedLoop.Destination;

/** Creates sliced loops with the shared event-queue scheduler. */
public class SlicedLoopFactory {

  static final int DEFAULT_SLICE_THRESHOLD_NS = 4_000_000;
  static final int DEFAULT_YELLING_THRESHOLD_NS = 16_000_000;
  private final DeferredReactorEnqueue deferredReactorEnqueue;

  public SlicedLoopFactory(DeferredReactorEnqueue deferredReactorEnqueue) {
    this.deferredReactorEnqueue = deferredReactorEnqueue;
  }

  public <T> SlicedLoop<T> slicedLoop(
      String debugHint, Iterator<T> source, Destination<T> destination) {
    return slicedLoop(DEFAULT_SLICE_THRESHOLD_NS, debugHint, source, destination);
  }

  public <T> SlicedLoop<T> slicedLoop(
      int sliceThresholdNs, String debugHint, Iterator<T> source, Destination<T> destination) {
    return slicedLoop(
        sliceThresholdNs, DEFAULT_YELLING_THRESHOLD_NS, debugHint, source, destination);
  }

  public <T> SlicedLoop<T> slicedLoop(
      int sliceThresholdNs,
      int yellingThresholdNs,
      String debugHint,
      Iterator<T> source,
      Destination<T> destination) {
    return new SlicedLoop<>(
        deferredReactorEnqueue,
        sliceThresholdNs,
        yellingThresholdNs,
        debugHint,
        source,
        destination);
  }
}
