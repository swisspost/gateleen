package org.swisspush.gateleen.expansion;

import static java.lang.Math.max;
import static java.lang.System.nanoTime;

import io.vertx.core.Context;
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
 * <p>One instance may be shared across Vert.x contexts so separate loops share the same enqueue
 * limit. Each task runs on the Vert.x context that enqueued it.
 */
public class DeferredReactorEnqueue {

  private static final long FLOATING_AVERAGE_BACKLOG = 50;
  private static final Logger log = LoggerFactory.getLogger(DeferredReactorEnqueue.class);
  private final Vertx vertx;
  private final AtomicInteger enqueuedTaskCount = new AtomicInteger();
  private final Queue<QueuedTask> queuedTasks = new ArrayDeque<>();
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
    Context context = Vertx.currentContext();
    if (context == null) {
      context = vertx.getOrCreateContext();
    }
    synchronized (queuedTasks) {
      queuedTasks.add(new QueuedTask(context, task));
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

    QueuedTask nextTask;
    synchronized (queuedTasks) {
      nextTask = queuedTasks.poll();
    }
    if (nextTask == null) {
      return;
    }

    enqueuedTaskCount.incrementAndGet();
    long delayMs = max(16, averageDurationNs.get() / 1_000_000);
    // tryNext() may run on another loop's context; the task must run on the context that queued it.
    if (Vertx.currentContext() == nextTask.context) {
      startTimer(nextTask, delayMs);
    } else {
      nextTask.context.runOnContext(v -> startTimer(nextTask, delayMs));
    }
  }

  private void startTimer(QueuedTask queuedTask, long delayMs) {
    vertx.setTimer(
        delayMs,
        timerId -> {
          enqueuedTaskCount.decrementAndGet();
          long startNs = nanoTime();
          try {
            queuedTask.task.run();
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

  private static final class QueuedTask {
    private final Context context;
    private final Runnable task;

    private QueuedTask(Context context, Runnable task) {
      this.context = context;
      this.task = task;
    }
  }
}
