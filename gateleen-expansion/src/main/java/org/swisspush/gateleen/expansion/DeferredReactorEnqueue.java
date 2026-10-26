package org.swisspush.gateleen.expansion;

import static java.lang.Math.max;
import static java.lang.System.nanoTime;

import io.vertx.core.Vertx;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spreads work submitted by {@link SlicedLoop} over the Vert.x event queue.
 *
 * <p>Use one instance per Vert.x event queue so separate loops share the same enqueue limit.
 */
public class DeferredReactorEnqueue {

  private static final long FLOATING_AVERAGE_BACKLOG = 50;
  private static final Logger log = LoggerFactory.getLogger(DeferredReactorEnqueue.class);
  private final Vertx vertx;
  private final AtomicInteger enqueuedTaskCount = new AtomicInteger();
  private final Queue<Runnable> queuedTasks = new ArrayDeque<>();
  private final AtomicLong averageDurationNs = new AtomicLong(10_000_000);

  public DeferredReactorEnqueue(Vertx vertx) {
    this.vertx = vertx;
  }

  public int getPendingTaskCount() {
    synchronized (queuedTasks) {
      return enqueuedTaskCount.get() + queuedTasks.size();
    }
  }

  public void enqueue(Runnable task) {
    synchronized (queuedTasks) {
      queuedTasks.add(task);
    }
    tryNext();
  }

  private void tryNext() {
    int inProgress = enqueuedTaskCount.get();
    float enqueueLimit = 10_000_000f / averageDurationNs.get();
    if (inProgress > 0 && inProgress > enqueueLimit) {
      log.debug("Already enqueued {} tasks to reactor. Postpone further enqueuing.", inProgress);
      return;
    }

    Runnable nextTask;
    synchronized (queuedTasks) {
      nextTask = queuedTasks.poll();
    }
    if (nextTask == null) {
      return;
    }

    enqueuedTaskCount.incrementAndGet();
    vertx.setTimer(
        max(16, averageDurationNs.get() / 1_000_000),
        timerId -> {
          enqueuedTaskCount.decrementAndGet();
          long startNs = nanoTime();
          try {
            nextTask.run();
          } finally {
            updateAverageDuration(Math.abs(nanoTime() - startNs));
            tryNext();
          }
        });
  }

  private void updateAverageDuration(long durationNs) {
    long previous;
    long updated;
    do {
      previous = averageDurationNs.get();
      updated = (previous * (FLOATING_AVERAGE_BACKLOG - 1) + durationNs) / FLOATING_AVERAGE_BACKLOG;
    } while (!averageDurationNs.compareAndSet(previous, updated));

    if (durationNs > 256_000_000L) {
      log.warn("Task took {} ns", durationNs);
    } else if (durationNs > 16_000_000L) {
      log.debug("Task took {} ns", durationNs);
    } else {
      log.trace("Task took {} ns", durationNs);
    }
  }
}
