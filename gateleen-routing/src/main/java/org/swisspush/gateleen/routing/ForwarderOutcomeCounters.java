package org.swisspush.gateleen.routing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The outcome counters ({@code gateleen_forwarded_requests}, {@code gateleen_forwarded_retries}
 * and {@code gateleen_forwarded_failures}) of one {@link Forwarder}, registered with reference
 * counting.
 * <p>
 * Forwarders with the same tags share the same meters. Each forwarder {@link #acquire acquires}
 * them when it gets a registry and {@link #release releases} them on shutdown; the meters are
 * removed from the registry only when the last forwarder using them released them. This bounds
 * the series of dynamically created forwarders (e.g. hook routes, which are tagged with their own
 * URL pattern) to the currently existing ones, while a replacement forwarder that acquires the
 * meters before its predecessor releases them (e.g. a refreshed hook registration) keeps
 * counting on the same meters without a reset.
 */
final class ForwarderOutcomeCounters {

    /** Number of acquisitions per meter, per registry. Guarded by the class monitor. */
    private static final Map<MeterRegistry, Map<Meter.Id, Integer>> USERS = new WeakHashMap<>();

    private final MeterRegistry registry;
    private final Counter requests;
    private final Counter retries;
    private final Counter failures;
    /** Guarded by the class monitor. */
    private boolean released;

    /**
     * @param registry the registry the counters are registered with
     * @param requests the {@code gateleen_forwarded_requests} counter
     * @param retries  the {@code gateleen_forwarded_retries} counter
     * @param failures the {@code gateleen_forwarded_failures} counter
     */
    private ForwarderOutcomeCounters(MeterRegistry registry, Counter requests, Counter retries, Counter failures) {
        this.registry = registry;
        this.requests = requests;
        this.retries = retries;
        this.failures = failures;
    }

    /**
     * Registers (or looks up the already registered) outcome counters with the given tags and
     * counts one more user for each of them.
     *
     * @param registry the registry to register the counters with
     * @param tags     the tags of all three counters
     * @return the acquired counters; {@link #release()} them when they are no longer used
     */
    static ForwarderOutcomeCounters acquire(MeterRegistry registry, Tags tags) {
        synchronized (ForwarderOutcomeCounters.class) {
            ForwarderOutcomeCounters counters = new ForwarderOutcomeCounters(registry,
                    register(registry, Forwarder.FORWARDED_REQUESTS_METRIC_NAME,
                            Forwarder.FORWARDED_REQUESTS_METRIC_DESCRIPTION, tags),
                    register(registry, Forwarder.FORWARDED_RETRIES_METRIC_NAME,
                            Forwarder.FORWARDED_RETRIES_METRIC_DESCRIPTION, tags),
                    register(registry, Forwarder.FORWARDED_FAILURES_METRIC_NAME,
                            Forwarder.FORWARDED_FAILURES_METRIC_DESCRIPTION, tags));
            Map<Meter.Id, Integer> users = USERS.computeIfAbsent(registry, r -> new HashMap<>());
            for (Counter counter : counters.all()) {
                users.merge(counter.getId(), 1, Integer::sum);
            }
            return counters;
        }
    }

    /**
     * Registers one counter, or returns the already registered counter with the same name and tags.
     *
     * @param registry    the registry to register the counter with
     * @param name        the metric name
     * @param description the metric description
     * @param tags        the metric tags
     * @return the registered counter
     */
    private static Counter register(MeterRegistry registry, String name, String description, Tags tags) {
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }

    /**
     * Counts one user less for each counter and removes the counters nobody uses anymore from the
     * registry. Calling it more than once has no further effect.
     */
    void release() {
        synchronized (ForwarderOutcomeCounters.class) {
            if (released) {
                return;
            }
            released = true;
            Map<Meter.Id, Integer> users = USERS.get(registry);
            for (Counter counter : all()) {
                Integer remaining = users == null ? null : users.computeIfPresent(counter.getId(), (id, n) -> n > 1 ? n - 1 : null);
                if (remaining == null) {
                    registry.remove(counter);
                }
            }
            if (users != null && users.isEmpty()) {
                USERS.remove(registry);
            }
        }
    }

    /**
     * Records the outcome of one forwarded request: one request, its additional attempts and, if
     * it failed, one failure. The increments happen while synchronized on the {@link MeterRegistry}
     * instance (see {@link Forwarder} and the routing README): consumers that read the three
     * counters as a consistent set synchronize on the same instance, so a request and its
     * retries/failure never fall into different sampling intervals. Keep the critical section
     * minimal, as all event loops pass it once per forwarded request.
     *
     * @param retries number of additional attempts ({@code attempt - 1}); {@code 0} for none
     * @param failed  whether the request finally failed
     */
    void record(long retries, boolean failed) {
        synchronized (registry) {
            requests.increment();
            if (retries > 0) {
                this.retries.increment(retries);
            }
            if (failed) {
                failures.increment();
            }
        }
    }

    /** @return the {@code gateleen_forwarded_requests} counter */
    Counter requests() {
        return requests;
    }

    /** @return the {@code gateleen_forwarded_retries} counter */
    Counter retries() {
        return retries;
    }

    /** @return the {@code gateleen_forwarded_failures} counter */
    Counter failures() {
        return failures;
    }

    /** @return all three counters */
    private List<Counter> all() {
        return List.of(requests, retries, failures);
    }
}
