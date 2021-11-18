package org.swisspush.gateleen.core.util;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.System.nanoTime;

/**
 * There should only exist ONE such instance per event queue.
 *
 * HINT: The word "queue" here neither has anything to do with the gateleen
 *       queues nor with redisques.
 *
 * Helps to better distribute load. This is especially handy in cases we write
 * code which produces hundreds of tasks for the event-loop. If we just directly
 * schedule all those tasks directly to the event queue, other tasks also get
 * delayed until all our tasks are processed. This can lead to problems
 * especially when the other tasks should be fast.
 *
 * In our concrete case it was the ExpansionHandler which produces tons of such
 * tasks each of them consuming a lot of time on the event-loop. In the end this
 * resulted in other (totally unrelated) HTTP requests which then ran into
 * timeouts randomly and everywhere.
 */
public class DeferredReactorEnqueue {

    private static final long FLOATAVG_BACKLOG = 50;
    private static final Logger log = LoggerFactory.getLogger(DeferredReactorEnqueue.class);
    private final Vertx vertx;
    private final AtomicInteger numEnqueuedTasks = new AtomicInteger(0);
    private final Queue<Runnable> heldbackTasks = new ArrayDeque<>();
    private final AtomicLong avgDurationNs = new AtomicLong(10_000_000);

    /** See {@link DeferredReactorEnqueue} */
    public DeferredReactorEnqueue(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * Returns how many tasks currently are waiting to get some CPU time. Can be
     * an interesting value for metrics for example.
     */
    public int getPendingTaskCount() {
        int size;
        synchronized (heldbackTasks) {
            size = heldbackTasks.size();
        }
        return numEnqueuedTasks.get() + size;
    }

    /**
     * Is a wrapper around {@link Vertx#setTimer(long, Handler)}. But dynamically
     * adapts enqueuing rate by learning the behavior of the tasks to keep
     * event-loop responsive.
     */
    public void enqueue(Runnable task) {
        synchronized (heldbackTasks) {
            heldbackTasks.add(task);
        }
        tryNext();
    }

    private void tryNext() {
        int alreadyInProgressCount = numEnqueuedTasks.get();
        float limitBasedOnAvgDuration = 10_000_000f / avgDurationNs.get();
        log.trace("limitBasedOnAvgDuration -> {}", limitBasedOnAvgDuration);
        if (alreadyInProgressCount > 0 && alreadyInProgressCount > limitBasedOnAvgDuration) {
            log.debug("Already enqueued {} tasks to reactor. Postpone further enqueuing.", alreadyInProgressCount);
            return;
        }
        Runnable nextTask;
        synchronized (heldbackTasks) {
            nextTask = heldbackTasks.poll();
        }
        if (nextTask == null) {
            log.trace("No more tasks to enqueue. {} tasks still in progress.", alreadyInProgressCount);
            return;
        }
        int thisTask = numEnqueuedTasks.incrementAndGet();
        log.trace("Enqueue task {} from our low-prio line.", thisTask);
        // MUST NOT enqueue faster than 16ms. Lower values kill performance on
        // all other requests. Will gradually slow-down the enqueuing-rate
        // depending on how fast the tasks in average are to not flood the
        // event-queue of the reactor.
        vertx.setTimer(Math.max(16,avgDurationNs.get()/1_000_000), tmrId -> {
            numEnqueuedTasks.decrementAndGet();
            long startNs = nanoTime();
            try {
                nextTask.run();
            } finally {
                long durationNs = Math.abs(nanoTime() - startNs);
                if (durationNs > Long.MAX_VALUE / 2) {
                    durationNs += Long.MAX_VALUE; // Fix overflow.
                }
                adjustFloatingAverageDuration(durationNs);
                tryNext();
            }
        });
    }

    private void adjustFloatingAverageDuration(long durationNs) {
        long oldVal = avgDurationNs.get();
        long newVal = (oldVal * (FLOATAVG_BACKLOG-1) + durationNs) / FLOATAVG_BACKLOG;
        if (log.isTraceEnabled()) {
            log.trace(String.format("%s%7f%s%7f%s%7f",
                    "avgDurationMillis  newVal: ", durationNs / 1000000f, ", oldAvg: ", oldVal / 1000000f, ", newAvg: ", newVal / 1000000f));
        }
        avgDurationNs.compareAndSet(oldVal, newVal);
        if (durationNs > 256_000_000L) {
            // 256 millis is a suspicious long duration for such a task. This
            // means that the whole app is blocked during a quarter of a second
            // and unable to react to any other requests at all.
            log.warn("Task took {} ns", durationNs);
        } else if (durationNs > 16_000_000L) {
            // Still a lot. But can happen here and there.
            log.debug("Task took {} ns", durationNs);
        } else {
            log.trace("Task took {} ns", durationNs);
        }
    }

}
