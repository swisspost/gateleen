package org.swisspush.gateleen.core.util;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

import static java.lang.System.nanoTime;

/**
 * An asynchronous loop which splits the CPU usage to multiple reactor tasks.
 *
 * The basic idea of the reactor pattern, event-loop, etc is to perform short
 * tasks and then release the thread so other tasks can do their work too. In
 * such a cooperative multithreading scenario, threads have to give up the
 * thread and MUST NOT do heavy work in one go. Using non-blocking APIs in most
 * cases is enough and threads will split-up their work by nature through those
 * calls.
 *
 * Loops can be evil in such an environment. As they initially "seem" to just
 * consume small amount of time but this can change drastically for unexpectedly
 * large iterations. Handling such cases for each loop individually is not easy
 * and error prone. This class tries to provide an easy (non-blocking) API to
 * implement such loops without the need to think too much about it.
 *
 * @param <T>
 *     Type of the elements to iterate.
 */
public class SlicedLoop<T> {

    static final int DEFAULT_SLICE_THRESHOLD_NS = 4_000_000;
    static final int DEFAULT_YELLING_THRESHOLD_NS = 16_000_000;
    static final String DEFAULT_DBG_HINT = "Follow the stack to see who created the EventLoop-hog";
    private static final long yellingCoolDownMs = 600_000;
    private static long lastYellingEpochMs = 0;
    private static final Logger log = LoggerFactory.getLogger(SlicedLoop.class);
    private final Vertx vertx;
    private final DeferredReactorEnqueue deferredReactorEnqueue;
    private final Iterator<T> source;
    private final Destination<T> dst;
    private final int sliceThresholdNs;
    private final int yellingThresholdNs;
    private final RuntimeException stackOfCreator;
    private boolean isRunning = false;
    private boolean pauseRequest = false;
    private boolean endReached = false;
    private long numElems = 0;
    private int numTasks = 0;

    /**
     * @param sliceThresholdNs
     *      Duration in nanoseconds after which the iteration gets paused and resumed later.
     * @param yellingThresholdNs
     *      If a task (alias: slice) exceeds this duration limit in nanoseconds,
     *      this will be logged.
     * @param debugHint
     *      Makes bug-hunting easier as this string gets used in case a EventLoop
     *      hog gets reported.
     * @param src
     *      The input to iterate.
     * @param dst
     *      Performs the work to be done inside the loop.
     */
    SlicedLoop(Vertx vertx, DeferredReactorEnqueue deferredReactorEnqueue, int sliceThresholdNs, int yellingThresholdNs, String debugHint, Iterator<T> src, Destination<T> dst) {
        this.vertx = vertx;
        this.deferredReactorEnqueue = deferredReactorEnqueue;
        this.source = src;
        this.dst = dst;
        this.sliceThresholdNs = sliceThresholdNs;
        this.yellingThresholdNs = yellingThresholdNs;
        // Need to take stack trace early. Because in the place we need it, the
        // problematic code is no longer in our stack.
        this.stackOfCreator = new EventLoopHogException(debugHint == null ? DEFAULT_DBG_HINT : debugHint);
    }

    /**
     * Explicitly requests to pause the iteration. Think for {@link HttpClientRequest#pause()}.
     */
    public void pause() {
        pauseRequest = true;
    }

    /**
     * Resume (or start) the paused iteration. Think for {@link HttpClientRequest#resume()}.
     */
    public void resume() {
        if (isRunning){
            throw new IllegalStateException("Already running");
        }
        pauseRequest = false;
        isRunning = true;
        enqueueNextSlice();
    }

    private void enqueueNextSlice() {
        if ( ! endReached) {
            deferredReactorEnqueue.enqueue(() -> {
                enqueueNextSlice();
                iterateNextSlice();
            });
        }
    }

    private void iterateNextSlice() {
        long sliceStartNs = nanoTime();
        long iChild = 0;
        numTasks += 1;
        for (;; ++iChild) {
            if (pauseRequest) {
                pauseRequest = false;
                isRunning = false; // <- Reset barrier so client is able to resume.
                return; // Abort current iteration.
            }
            if ( ! source.hasNext()) {
                // End of iteration reached. No more elements to process. We're done.
                log.trace("Broke down iteration of {} elements into {} tasks.", numElems, numTasks);
                endReached = true;
                try {
                    dst.onEnd();
                } catch (RuntimeException e) {
                    dst.onError(e);
                }
                return;
            }
            // Process next element
            numElems += 1;
            try {
                dst.onNext(source.next());
            } catch (RuntimeException e) {
                pauseRequest = true; // Don't process any more elements after an error.
                dst.onError(e);
                return;
            }
            long nowNs = nanoTime();
            long usedCpuNs = nowNs - sliceStartNs;
            if (usedCpuNs > Long.MAX_VALUE / 2) {
                // nanoTime did overflow since we measured start point. Unlikely, but
                // still can happen. See JavaDoc of nanoTime(). Applying yet another
                // overflow on the difference by signed-max-value reverts the effect
                // and we end up with our expected difference.
                usedCpuNs += Long.MAX_VALUE;
            }
            if (usedCpuNs > sliceThresholdNs) {
                if (usedCpuNs > yellingThresholdNs) {
                    // This case does not match my definition of a "good" task. So we'll yell
                    // loud about it. But we'll log only a few of them on WARN level to not
                    // kill performance due to logging.
                    long nowEpochMs = System.currentTimeMillis();
                    if (lastYellingEpochMs + yellingCoolDownMs < nowEpochMs) {
                        lastYellingEpochMs = nowEpochMs;
                        log.warn("Task i={} blocked event-loop for {} ms.", iChild, usedCpuNs/1_000_000f, this.stackOfCreator);
                    } else {
                        log.trace("Task i={} blocked event-loop for {} ms.", iChild, usedCpuNs/1_000_000f, this.stackOfCreator);
                    }
                } else {
                    log.trace("Slice-quota of {} ns exceeded ({} turns consumed {} ns). Give up CPU and continue later.", sliceThresholdNs, iChild + 1, usedCpuNs);
                }
                // Slice-quota exceeded. Give up CPU and continue later.
                return;
            }
        }
    }

    public  interface Destination<T> {
        void onNext(T e);
        default void onError(RuntimeException e) { throw e; }
        default void onEnd() {}
    }

    /**
     * Does not get thrown. Only used to log stack-traces of code which hog
     * the event-loop for too long.
     */
    public static class EventLoopHogException extends RuntimeException {
        private EventLoopHogException(String message) {
            super(message);
        }
    }
}
