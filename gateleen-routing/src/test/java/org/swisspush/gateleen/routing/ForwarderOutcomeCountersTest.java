package org.swisspush.gateleen.routing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for the reference counting and recording of {@link ForwarderOutcomeCounters}.
 */
public class ForwarderOutcomeCountersTest {

    private static final Tags TAGS_A = Tags.of("metricName", "a", "rule", "/a/.*", "target", "host:1");
    private static final Tags TAGS_B = Tags.of("metricName", "b", "rule", "/b/.*", "target", "host:2");
    private static final List<String> NAMES = List.of(Forwarder.FORWARDED_REQUESTS_METRIC_NAME,
            Forwarder.FORWARDED_RETRIES_METRIC_NAME, Forwarder.FORWARDED_FAILURES_METRIC_NAME);

    private SimpleMeterRegistry registry;

    /** Creates a fresh registry per test. */
    @Before
    public void setUp() {
        registry = new SimpleMeterRegistry();
    }

    /** Closes the registry of the test. */
    @After
    public void tearDown() {
        registry.close();
    }

    /** Acquiring registers all three counters with the given tags and descriptions. */
    @Test
    public void testAcquireRegistersAllCounters() {
        ForwarderOutcomeCounters counters = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
        assertSame(counters.requests(), find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME, "a"));
        assertSame(counters.retries(), find(Forwarder.FORWARDED_RETRIES_METRIC_NAME, "a"));
        assertSame(counters.failures(), find(Forwarder.FORWARDED_FAILURES_METRIC_NAME, "a"));
        assertEquals(Forwarder.FORWARDED_FAILURES_METRIC_DESCRIPTION, counters.failures().getId().getDescription());
        assertEquals(3, registry.getMeters().size());
    }

    /** Recording counts one request, the given retries and a failure only if the request failed. */
    @Test
    public void testRecord() {
        ForwarderOutcomeCounters counters = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
        counters.record(0, false);
        counters.record(3, true);
        counters.record(1, false);
        assertEquals(3d, counters.requests().count(), 0d);
        assertEquals(4d, counters.retries().count(), 0d);
        assertEquals(1d, counters.failures().count(), 0d);
    }

    /** Releasing the only user removes all three counters from the registry. */
    @Test
    public void testReleaseOfLastUserRemovesCounters() {
        ForwarderOutcomeCounters.acquire(registry, TAGS_A).release();
        assertEquals(0, registry.getMeters().size());
    }

    /**
     * Two users share the same meters; releasing one keeps the meters and their values, releasing
     * the other removes them. Repeated releases of the same instance count once.
     */
    @Test
    public void testSharedCountersAreRemovedWithLastUser() {
        ForwarderOutcomeCounters first = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
        ForwarderOutcomeCounters second = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
        assertSame(first.requests(), second.requests());
        first.record(2, true);

        first.release();
        first.release();
        assertEquals(3, registry.getMeters().size());
        assertEquals(1d, find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME, "a").count(), 0d);
        assertEquals(2d, find(Forwarder.FORWARDED_RETRIES_METRIC_NAME, "a").count(), 0d);

        second.release();
        assertEquals(0, registry.getMeters().size());
    }

    /**
     * A successor acquired before its predecessor is released (hook refresh) keeps counting on the
     * same meters without reset.
     */
    @Test
    public void testSuccessorAcquiredBeforeReleaseKeepsValues() {
        ForwarderOutcomeCounters predecessor = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
        predecessor.record(0, false);
        ForwarderOutcomeCounters successor = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
        predecessor.release();
        successor.record(0, false);
        assertEquals(2d, find(Forwarder.FORWARDED_REQUESTS_METRIC_NAME, "a").count(), 0d);
    }

    /** Releasing counters of one tag set does not touch counters with other tags. */
    @Test
    public void testReleaseOnlyRemovesOwnTags() {
        ForwarderOutcomeCounters a = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
        ForwarderOutcomeCounters.acquire(registry, TAGS_B);
        a.release();
        for (String name : NAMES) {
            assertNull(registry.find(name).tag("metricName", "a").counter());
            assertNotNull(registry.find(name).tag("metricName", "b").counter());
        }
    }

    /** Reference counts are kept per registry: releasing in one registry leaves the other intact. */
    @Test
    public void testReferenceCountsArePerRegistry() {
        SimpleMeterRegistry other = new SimpleMeterRegistry();
        try {
            ForwarderOutcomeCounters inRegistry = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
            ForwarderOutcomeCounters.acquire(other, TAGS_A);
            inRegistry.release();
            assertEquals(0, registry.getMeters().size());
            assertEquals(3, other.getMeters().size());
        } finally {
            other.close();
        }
    }

    /** After all users released the counters, acquiring again registers fresh counters starting at zero. */
    @Test
    public void testReacquireAfterRemovalStartsFresh() {
        ForwarderOutcomeCounters old = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
        old.record(0, false);
        old.release();
        ForwarderOutcomeCounters fresh = ForwarderOutcomeCounters.acquire(registry, TAGS_A);
        assertEquals(0d, fresh.requests().count(), 0d);
        fresh.release();
        assertEquals(0, registry.getMeters().size());
    }

    /**
     * Looks up a registered outcome counter by name and {@code metricName} tag.
     *
     * @param name       the metric name
     * @param metricName the {@code metricName} tag value
     * @return the counter, or {@code null} if not registered
     */
    private Counter find(String name, String metricName) {
        return registry.find(name).tag("metricName", metricName).counter();
    }
}
