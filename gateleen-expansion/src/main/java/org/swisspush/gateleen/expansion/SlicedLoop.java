package org.swisspush.gateleen.expansion;

import static java.lang.System.nanoTime;

import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** An asynchronous loop that splits CPU work into short event-loop tasks. */
public class SlicedLoop<T> {

  static final String DEFAULT_DEBUG_HINT = "Follow the stack to see who created the EventLoop-hog";
  private static final long YELLING_COOLDOWN_MS = 600_000;
  private static final Logger log = LoggerFactory.getLogger(SlicedLoop.class);
  private static long lastYellingEpochMs;
  private final DeferredReactorEnqueue deferredReactorEnqueue;
  private final Iterator<T> source;
  private final Destination<T> destination;
  private final int sliceThresholdNs;
  private final int yellingThresholdNs;
  private final RuntimeException stackOfCreator;
  private boolean isRunning;
  private boolean pauseRequested;
  private boolean endReached;
  private long elementCount;
  private int taskCount;

  SlicedLoop(
      DeferredReactorEnqueue deferredReactorEnqueue,
      int sliceThresholdNs,
      int yellingThresholdNs,
      String debugHint,
      Iterator<T> source,
      Destination<T> destination) {
    this.deferredReactorEnqueue = deferredReactorEnqueue;
    this.source = source;
    this.destination = destination;
    this.sliceThresholdNs = sliceThresholdNs;
    this.yellingThresholdNs = yellingThresholdNs;
    stackOfCreator = new EventLoopHogException(debugHint == null ? DEFAULT_DEBUG_HINT : debugHint);
  }

  public void pause() {
    pauseRequested = true;
  }

  public void resume() {
    if (isRunning) {
      throw new IllegalStateException("Already running");
    }
    pauseRequested = false;
    isRunning = true;
    enqueueNextSlice();
  }

  private void enqueueNextSlice() {
    if (!endReached) {
      deferredReactorEnqueue.enqueue(
          () -> {
            // The next slice is queued ahead of time, so a pause must stop the chain right here.
            if (pauseRequested) {
              pauseRequested = false;
              isRunning = false;
              return;
            }
            enqueueNextSlice();
            iterateNextSlice();
          });
    }
  }

  private void iterateNextSlice() {
    long sliceStartNs = nanoTime();
    long childIndex = 0;
    taskCount++;

    for (; ; childIndex++) {
      if (pauseRequested) {
        return;
      }

      if (!source.hasNext()) {
        log.trace("Broke down iteration of {} elements into {} tasks.", elementCount, taskCount);
        if (!endReached) {
          endReached = true;
          try {
            destination.onEnd();
          } catch (RuntimeException e) {
            destination.onError(e);
          }
        }
        return;
      }

      elementCount++;
      try {
        destination.onNext(source.next());
      } catch (RuntimeException e) {
        pauseRequested = true;
        destination.onError(e);
        return;
      }

      long usedCpuNs = nanoTime() - sliceStartNs;
      if (usedCpuNs > Long.MAX_VALUE / 2) {
        usedCpuNs += Long.MAX_VALUE;
      }
      if (usedCpuNs > sliceThresholdNs) {
        if (usedCpuNs > yellingThresholdNs) {
          long nowEpochMs = System.currentTimeMillis();
          if (lastYellingEpochMs + YELLING_COOLDOWN_MS < nowEpochMs) {
            lastYellingEpochMs = nowEpochMs;
            log.warn(
                "Task i={} blocked event-loop for {} ms.",
                childIndex,
                usedCpuNs / 1_000_000f,
                stackOfCreator);
          } else {
            log.trace(
                "Task i={} blocked event-loop for {} ms.",
                childIndex,
                usedCpuNs / 1_000_000f,
                stackOfCreator);
          }
        } else {
          log.trace(
              "Slice-quota of {} ns exceeded ({} turns consumed {} ns). Give up CPU and continue"
                  + " later.",
              sliceThresholdNs,
              childIndex + 1,
              usedCpuNs);
        }
        return;
      }
    }
  }

  public interface Destination<T> {
    void onNext(T element);

    default void onError(RuntimeException e) {
      throw e;
    }

    default void onEnd() {}
  }

  /** Used only to capture the stack trace that created this loop. */
  public static class EventLoopHogException extends RuntimeException {
    private EventLoopHogException(String message) {
      super(message);
    }
  }
}
