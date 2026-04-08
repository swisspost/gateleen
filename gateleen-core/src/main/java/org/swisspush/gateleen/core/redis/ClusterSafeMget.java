package org.swisspush.gateleen.core.redis;

import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.impl.ZModem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static java.util.stream.Collectors.toList;

/**
 * Utility for performing Redis {@code MGET} operations safely against a Redis Cluster.
 *
 * <p>In Redis Cluster mode, all keys in a single command <em>must</em> reside on the same
 * hash slot (and therefore the same node). A plain {@code MGET} over keys that span multiple
 * slots would be rejected by the cluster with a {@code CROSSSLOT} error.</p>
 *
 * <p>This class solves the problem by:
 * <ol>
 *   <li>Computing the hash slot for every key using the same CRC-16/CCITT algorithm that
 *       Redis itself uses (see the
 *       <a href="https://redis.io/docs/reference/cluster-spec/#hash-tags">Redis Cluster Spec §3</a>).</li>
 *   <li>Grouping keys by their slot and issuing one {@code MGET} per group.</li>
 *   <li>Reassembling the per-slot responses back into a single list that matches the
 *       original input key order, so callers do not need to know about slot grouping.</li>
 * </ol>
 * </p>
 */
public class ClusterSafeMget {

    private static final Logger log = LoggerFactory.getLogger(ClusterSafeMget.class);

    // Static utility class — no instances needed.
    private ClusterSafeMget() {}

    /**
     * Fetches the values for all given keys from Redis, grouping them by hash slot so that
     * each individual {@code MGET} command stays within a single Redis Cluster node.
     *
     * <p>The returned list has exactly the same size and ordering as the input {@code keys}
     * list. A {@code null} entry at position {@code i} means that key was not found in Redis
     * (Redis returns a bulk-nil for missing keys).</p>
     *
     * @param redis the Redis API handle to issue commands on
     * @param keys  the keys to fetch; {@code null} or empty returns an already-completed
     *              future with an empty list without touching Redis
     * @return a future that completes with the list of responses in input order, or fails
     *         if any per-slot {@code MGET} fails
     */
    public static Future<List<Response>> clusterSafeMget(RedisAPI redis, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            log.debug("clusterSafeMget called with null or empty keys list, returning empty result");
            return Future.succeededFuture(Collections.emptyList());
        }

        // Map each slot number to the list of original indices whose key hashes to that slot.
        // We track indices (not the keys themselves) so we can write results back into the
        // correct positions of the output array after each per-slot mget completes.
        Map<Integer, List<Integer>> slotToIndices = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            int slot = getSlot(keys.get(i));
            slotToIndices.computeIfAbsent(slot, k -> new ArrayList<>()).add(i);
        }

        if (log.isTraceEnabled()) {
            log.trace("clusterSafeMget: {} key(s) distributed across {} slot(s)", keys.size(), slotToIndices.size());
        }

        // Pre-allocate the result array indexed by original key position.
        // Entries remain null for keys not found in Redis (Redis bulk-nil → null Response).
        List<Future<Void>> futures = new ArrayList<>();
        Response[] results = new Response[keys.size()];

        for (Map.Entry<Integer, List<Integer>> slotEntry : slotToIndices.entrySet()) {
            List<Integer> indices = slotEntry.getValue();
            // Reconstruct the ordered list of keys that belong to this slot.
            List<String> slotKeys = indices.stream()
                    .map(keys::get)
                    .collect(toList());

            // Issue a single MGET for all keys in this slot.
            // andThen() is used (instead of map()) so that we can inspect both success and
            // failure in one handler while still propagating the original success/failure
            // state of the future — map() would turn a failure into an unhandled exception.
            // mapEmpty() converts Future<Response> → Future<Void> so all slot futures have
            // a uniform type for Future.all().
            Future<Void> f = redis.mget(slotKeys)
                    .andThen(event -> {
                        if (event.failed()) {
                            log.error("mget command failed for slot {} with keys {}: {}",
                                    slotEntry.getKey(), slotKeys, event.cause().getMessage());
                            return;
                        }
                        Response response = event.result();
                        if (response != null) {
                            // response is an array-type Redis response; response.get(i) gives
                            // the value for slotKeys[i], or null if that key does not exist.
                            for (int i = 0; i < indices.size(); i++) {
                                results[indices.get(i)] = response.get(i); // may be null for missing keys
                            }
                        } else {
                            log.warn("mget returned null response for slot {} with keys {}", slotEntry.getKey(), slotKeys);
                        }
                    })
                    .mapEmpty();

            futures.add(f);
        }

        // Wait for all per-slot mgets to finish, then wrap the result array in a List.
        // If any slot future failed, Future.all() fails and the error is logged before
        // propagating to the caller.
        return Future.all(futures)
                .onFailure(ex -> log.error(
                        "clusterSafeMget failed: one or more slot mget operations did not succeed. Cause: {}",
                        ex.getMessage()
                ))
                .map(ignored -> Arrays.asList(results));
    }

    /**
     * Computes the Redis hash slot number (0–16383) for the given key.
     *
     * <p>Delegates to {@link ZModem#generate(String)}, which implements the same
     * CRC-16/CCITT-FALSE algorithm (including hash-tag handling) that Redis Cluster
     * uses internally.</p>
     *
     * @param key the Redis key (must not be {@code null})
     * @return slot number in the range [0, 16383]
     * @see <a href="https://redis.io/docs/reference/cluster-spec/#hash-tags">Redis Cluster Spec §3 – Hash tags</a>
     */
    public static int getSlot(String key) {
        return ZModem.generate(key);
    }
}
