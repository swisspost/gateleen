package org.swisspush.gateleen.core.redis;

import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
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
     * Pre-computed CRC-16/CCITT-FALSE lookup table (256 entries, one per possible byte value).
     *
     * <p>Using a table avoids recomputing 8 polynomial-division steps for every byte of every
     * key at runtime. The table is built once at class-load time and shared across all calls.</p>
     */
    private static final int[] CRC16_TABLE = buildCrc16Table();

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
     * <p>Redis Cluster divides the key space into 16384 slots. Each key is assigned to
     * exactly one slot by hashing it with CRC-16/CCITT-FALSE and taking the result modulo
     * 16384. Only keys in the same slot can be used together in multi-key commands.</p>
     *
     * <p><b>Hash tags</b> allow an application to force a set of related keys into the same
     * slot: if the key contains a substring enclosed in {@code {}} (e.g. {@code {user}.name}
     * and {@code {user}.email}), Redis hashes <em>only</em> the content between the braces
     * ({@code user} in this example) instead of the full key. This guarantees that all keys
     * sharing the same hash-tag content map to the same slot and can therefore be used in a
     * single {@code MGET}, {@code DEL}, etc.
     *
     * <p>Rules for hash-tag detection (matching the Redis Cluster Spec §3):
     * <ul>
     *   <li>Only the first {@code {…}} pair is considered.</li>
     *   <li>The content between the braces must be non-empty; {@code {}} is ignored and the
     *       full key is hashed instead.</li>
     *   <li>If there is no closing {@code }}, the full key is hashed.</li>
     * </ul>
     * </p>
     *
     * @param key the Redis key (must not be {@code null})
     * @return slot number in the range [0, 16383]
     * @see <a href="https://redis.io/docs/reference/cluster-spec/#hash-tags">Redis Cluster Spec §3 – Hash tags</a>
     */
    public static int getSlot(String key) {
        // Redis operates on raw bytes; use UTF-8 to match its own key encoding.
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int start = -1, end = -1;

        // Scan for the first '{' and the first '}' that follows it.
        for (int i = 0; i < keyBytes.length; i++) {
            if (keyBytes[i] == '{' && start == -1) start = i;
            else if (keyBytes[i] == '}' && start != -1) { end = i; break; }
        }

        // A valid hash tag exists only when the closing brace comes at least two positions
        // after the opening brace (i.e. there is at least one byte of tag content).
        // end > start + 1  ⟺  the slice [start+1, end) is non-empty.
        if (start != -1 && end != -1 && end > start + 1) {
            // Hash only the tag content (bytes between '{' and '}', exclusive).
            return crc16(keyBytes, start + 1, end) % 16384;
        }

        // No valid hash tag — hash the entire key.
        return crc16(keyBytes, 0, keyBytes.length) % 16384;
    }

    /**
     * Computes CRC-16/CCITT-FALSE over a slice of {@code data[start..end)}.
     *
     * <p>This is the specific CRC-16 variant mandated by the
     * <a href="https://redis.io/docs/reference/cluster-spec/#hash-tags">Redis Cluster Spec</a>.
     * It uses the CCITT generator polynomial {@code 0x1021} with an initial value of
     * {@code 0x0000} and no final XOR or bit-reversal — sometimes called CRC-16/CCITT-FALSE.</p>
     *
     * <p>The update step for each byte {@code b} is:
     * <pre>
     *   crc = ((crc &lt;&lt; 8) &amp; 0xFFFF) ^ TABLE[(crc &gt;&gt; 8) ^ b]
     * </pre>
     * Breaking this down:
     * <ul>
     *   <li>{@code crc >> 8} — isolates the high byte of the current CRC, which is about to
     *       be "shifted out" of the 16-bit register.</li>
     *   <li>{@code ^ (data[i] & 0xFF)} — XORs the incoming byte into the index; {@code & 0xFF}
     *       ensures the Java {@code byte} (signed) is treated as an unsigned value.</li>
     *   <li>{@code CRC16_TABLE[...]} — looks up the pre-computed contribution of that combined
     *       value (equivalent to running 8 polynomial-division steps).</li>
     *   <li>{@code (crc << 8) & 0xFFFF} — shifts the low byte of the old CRC into the high
     *       byte position and masks to 16 bits (Java {@code int} is 32-bit, so the mask
     *       prevents sign/overflow bits from leaking into the result).</li>
     * </ul>
     * </p>
     *
     * @param data  the byte array to checksum
     * @param start index of the first byte to include (inclusive)
     * @param end   index of the first byte to exclude (exclusive)
     * @return the 16-bit CRC as a non-negative {@code int}
     */
    private static int crc16(byte[] data, int start, int end) {
        int crc = 0x0000; // Initial value per CRC-16/CCITT-FALSE spec
        for (int i = start; i < end; i++) {
            crc = ((crc << 8) & 0xFFFF) ^ CRC16_TABLE[((crc >> 8) ^ (data[i] & 0xFF)) & 0xFF];
        }
        return crc;
    }

    /**
     * Pre-computes the CRC-16/CCITT-FALSE lookup table.
     *
     * <p>A lookup table is the standard optimisation for CRC computation: instead of looping
     * over all 8 bits of every input byte at runtime, we pre-compute the CRC contribution of
     * all 256 possible byte values once. This reduces the per-byte cost from 8 conditional
     * shift-and-XOR operations to a single array lookup plus one XOR.</p>
     *
     * <p>For each possible byte value {@code i} (0–255) the table entry is computed by
     * treating {@code i} as if it were the sole input to the CRC and running the full
     * 8-bit polynomial division:
     * <ol>
     *   <li>Place {@code i} in the high byte of a 16-bit register: {@code crc = i << 8}.</li>
     *   <li>For each of the 8 bits: if the most-significant bit (bit 15) is set, shift left
     *       and XOR with the CCITT polynomial {@code 0x1021}; otherwise just shift left.</li>
     *   <li>Mask to 16 bits with {@code & 0xFFFF} to discard carry bits produced by Java's
     *       32-bit {@code int} arithmetic.</li>
     * </ol>
     * </p>
     *
     * @return a 256-element array where {@code table[i]} is the CRC-16/CCITT-FALSE remainder
     *         for the single-byte input value {@code i}
     */
    private static int[] buildCrc16Table() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            // Seed the shift register with the byte value in the high byte position.
            int crc = i << 8;
            // Process all 8 bits of this byte through the generator polynomial.
            for (int j = 0; j < 8; j++) {
                // If the MSB is set, the polynomial divisor (0x1021) contributes to the remainder.
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
            // Mask to 16 bits — Java int is 32-bit, so shifts can set higher bits.
            table[i] = crc & 0xFFFF;
        }
        return table;
    }
}
