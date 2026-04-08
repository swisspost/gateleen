package org.swisspush.gateleen.core.redis;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.client.PoolOptions;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisStandaloneConnectOptions;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.impl.RedisClient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Tests for the {@link ClusterSafeMget} class
 */
@RunWith(VertxUnitRunner.class)
public class ClusterSafeMgetTest {

    // -------------------------------------------------------------------------
    // Mocked RedisAPI — used by unit tests (no real Redis needed)
    // -------------------------------------------------------------------------

    private RedisAPI redisAPI;

    @Before
    public void setUp() {
        redisAPI = Mockito.mock(RedisAPI.class);
    }

    // -------------------------------------------------------------------------
    // Real Redis infrastructure — used only by integration tests
    // -------------------------------------------------------------------------

    @org.junit.Rule
    public Timeout timeoutRule = Timeout.seconds(5);

    private static Vertx vertx;
    private static RedisAPI realRedisAPI;
    private Jedis jedis;

    @BeforeClass
    public static void setUpRealRedis() {
        vertx = Vertx.vertx();
        realRedisAPI = RedisAPI.api(new RedisClient(
                vertx, new NetClientOptions(), new PoolOptions(),
                new RedisStandaloneConnectOptions(), TracingPolicy.IGNORE));
    }

    @AfterClass
    public static void tearDownRealRedis() {
        if (vertx != null) {
            vertx.close();
        }
    }

    /**
     * Opens a synchronous Jedis connection before each test, flushes Redis to
     * guarantee a clean slate, and skips gracefully when no Redis is available.
     * The Jedis instance is used only for test-data setup and teardown; the
     * async {@link #realRedisAPI} is used for the actual commands under test.
     */
    private void connectJedis() {
        jedis = new Jedis("localhost");
        try {
            jedis.flushAll();
        } catch (JedisConnectionException e) {
            org.junit.Assume.assumeNoException(
                    "Ignoring this test because no running Redis is available. This is the case during release", e);
        }
    }

    @After
    public void closeJedis() {
        if (jedis != null) {
            jedis.close();
            jedis = null;
        }
    }

    // -------------------------------------------------------------------------
    // Guard conditions
    // -------------------------------------------------------------------------

    @Test
    public void testNullKeysReturnsEmptyList(TestContext context) {
        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, null).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            context.assertTrue(event.result().isEmpty());
            Mockito.verifyNoInteractions(redisAPI);
            async.complete();
        });
    }

    @Test
    public void testEmptyKeysReturnsEmptyList(TestContext context) {
        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, Collections.emptyList()).onComplete(event -> {
            context.assertTrue(event.succeeded());
            context.assertNotNull(event.result());
            context.assertTrue(event.result().isEmpty());
            Mockito.verifyNoInteractions(redisAPI);
            async.complete();
        });
    }

    // -------------------------------------------------------------------------
    // Single-key cases
    // -------------------------------------------------------------------------

    @Test
    public void testSingleKeySuccess(TestContext context) {
        Response mockValue = Mockito.mock(Response.class);
        Response mockResponse = Mockito.mock(Response.class);
        Mockito.when(mockResponse.get(0)).thenReturn(mockValue);
        Mockito.when(redisAPI.mget(anyList())).thenReturn(Future.succeededFuture(mockResponse));

        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, Collections.singletonList("foo")).onComplete(event -> {
            context.assertTrue(event.succeeded());
            List<Response> results = event.result();
            context.assertEquals(1, results.size());
            context.assertEquals(mockValue, results.get(0));
            async.complete();
        });
    }

    @Test
    public void testSingleKeyRedisFailure(TestContext context) {
        Mockito.when(redisAPI.mget(anyList())).thenReturn(Future.failedFuture("Redis connection error"));

        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, Collections.singletonList("foo")).onComplete(event -> {
            context.assertFalse(event.succeeded());
            async.complete();
        });
    }

    @Test
    public void testSingleKeyMissingInRedisProducesNullEntry(TestContext context) {
        // response.get(0) returns null when the key does not exist in Redis
        Response mockResponse = Mockito.mock(Response.class);
        Mockito.when(mockResponse.get(0)).thenReturn(null);
        Mockito.when(redisAPI.mget(anyList())).thenReturn(Future.succeededFuture(mockResponse));

        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, Collections.singletonList("foo")).onComplete(event -> {
            context.assertTrue(event.succeeded());
            List<Response> results = event.result();
            context.assertEquals(1, results.size());
            context.assertNull(results.get(0));
            async.complete();
        });
    }

    @Test
    public void testNullResponseForSlotProducesNullEntries(TestContext context) {
        // mget returns null Response (not just null value at index)
        Mockito.when(redisAPI.mget(anyList())).thenReturn(Future.succeededFuture(null));

        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, Collections.singletonList("foo")).onComplete(event -> {
            context.assertTrue(event.succeeded());
            List<Response> results = event.result();
            context.assertEquals(1, results.size());
            context.assertNull(results.get(0));
            async.complete();
        });
    }

    // -------------------------------------------------------------------------
    // Multi-key: same slot
    // -------------------------------------------------------------------------

    @Test
    public void testMultipleKeysSameSlotIssuesOneMget(TestContext context) {
        // {user}.1 and {user}.2 share hash tag "user" → same slot
        Response val0 = Mockito.mock(Response.class);
        Response val1 = Mockito.mock(Response.class);
        Response mockResponse = Mockito.mock(Response.class);
        Mockito.when(mockResponse.get(0)).thenReturn(val0);
        Mockito.when(mockResponse.get(1)).thenReturn(val1);
        Mockito.when(redisAPI.mget(anyList())).thenReturn(Future.succeededFuture(mockResponse));

        List<String> keys = Arrays.asList("{user}.1", "{user}.2");
        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, keys).onComplete(event -> {
            context.assertTrue(event.succeeded());
            // Exactly one mget call since both keys are in the same slot
            Mockito.verify(redisAPI, Mockito.times(1)).mget(anyList());
            List<Response> results = event.result();
            context.assertEquals(2, results.size());
            context.assertEquals(val0, results.get(0));
            context.assertEquals(val1, results.get(1));
            async.complete();
        });
    }

    // -------------------------------------------------------------------------
    // Multi-key: different slots
    // -------------------------------------------------------------------------

    @Test
    public void testMultipleKeysDifferentSlotsIssuesMultipleMgets(TestContext context) {
        // "foo" and "bar" hash to different slots
        context.assertNotEquals(ClusterSafeMget.getSlot("foo"), ClusterSafeMget.getSlot("bar"));

        Response valFoo = Mockito.mock(Response.class);
        Response valBar = Mockito.mock(Response.class);

        Response responseFoo = Mockito.mock(Response.class);
        Mockito.when(responseFoo.get(0)).thenReturn(valFoo);

        Response responseBar = Mockito.mock(Response.class);
        Mockito.when(responseBar.get(0)).thenReturn(valBar);

        // Return different responses for successive calls
        Mockito.when(redisAPI.mget(anyList()))
                .thenReturn(Future.succeededFuture(responseFoo))
                .thenReturn(Future.succeededFuture(responseBar));

        List<String> keys = Arrays.asList("foo", "bar");
        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, keys).onComplete(event -> {
            context.assertTrue(event.succeeded());
            // Two mget calls — one per slot
            Mockito.verify(redisAPI, Mockito.times(2)).mget(anyList());
            List<Response> results = event.result();
            context.assertEquals(2, results.size());
            // Each result must be one of the two mock values (order is preserved by index)
            context.assertTrue(results.contains(valFoo));
            context.assertTrue(results.contains(valBar));
            async.complete();
        });
    }

    @Test
    public void testOneSlotFailureCausesOverallFailure(TestContext context) {
        // "foo" and "bar" are in different slots; fail the second mget
        Mockito.when(redisAPI.mget(anyList()))
                .thenReturn(Future.succeededFuture(Mockito.mock(Response.class)))
                .thenReturn(Future.failedFuture("slot error"));

        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, Arrays.asList("foo", "bar")).onComplete(event -> {
            context.assertFalse(event.succeeded());
            async.complete();
        });
    }

    // -------------------------------------------------------------------------
    // Order preservation
    // -------------------------------------------------------------------------

    @Test
    public void testResultOrderMatchesInputKeyOrder(TestContext context) {
        // Use three keys: two in the same slot (hash-tagged) + one in a different slot.
        // Input order: [{user}.1, standalone, {user}.2]
        // The results array must preserve that order.
        context.assertNotEquals(
                ClusterSafeMget.getSlot("{user}.1"),
                ClusterSafeMget.getSlot("standalone")
        );
        context.assertEquals(
                ClusterSafeMget.getSlot("{user}.1"),
                ClusterSafeMget.getSlot("{user}.2")
        );

        Response valUser1 = Mockito.mock(Response.class);
        Response valStandalone = Mockito.mock(Response.class);
        Response valUser2 = Mockito.mock(Response.class);

        // mget for the {user} slot returns [valUser1, valUser2]
        Response userSlotResponse = Mockito.mock(Response.class);
        Mockito.when(userSlotResponse.get(0)).thenReturn(valUser1);
        Mockito.when(userSlotResponse.get(1)).thenReturn(valUser2);

        // mget for the standalone slot returns [valStandalone]
        Response standaloneResponse = Mockito.mock(Response.class);
        Mockito.when(standaloneResponse.get(0)).thenReturn(valStandalone);

        Mockito.when(redisAPI.mget(anyList()))
                .thenReturn(Future.succeededFuture(userSlotResponse))
                .thenReturn(Future.succeededFuture(standaloneResponse));

        List<String> keys = Arrays.asList("{user}.1", "standalone", "{user}.2");
        Async async = context.async();
        ClusterSafeMget.clusterSafeMget(redisAPI, keys).onComplete(event -> {
            context.assertTrue(event.succeeded());
            List<Response> results = event.result();
            context.assertEquals(3, results.size());
            context.assertEquals(valUser1,     results.get(0)); // {user}.1
            context.assertEquals(valStandalone, results.get(1)); // standalone
            context.assertEquals(valUser2,     results.get(2)); // {user}.2
            async.complete();
        });
    }

    // -------------------------------------------------------------------------
    // getSlot — determinism and hash-tag behaviour
    // -------------------------------------------------------------------------

    @Test
    public void testGetSlotIsDeterministic(TestContext context) {
        int slot1 = ClusterSafeMget.getSlot("foo");
        int slot2 = ClusterSafeMget.getSlot("foo");
        context.assertEquals(slot1, slot2);
    }

    @Test
    public void testGetSlotIsInValidRange(TestContext context) {
        // Redis has 16384 slots (0–16383)
        int slot = ClusterSafeMget.getSlot("some-random-key");
        context.assertTrue(slot >= 0 && slot < 16384);
    }

    @Test
    public void testGetSlotKnownValue(TestContext context) {
        // Actual CRC16-CCITT("foo") mod 16384 = 12182 (verified against the implementation)
        context.assertEquals(12182, ClusterSafeMget.getSlot("foo"));
    }

    @Test
    public void testGetSlotDifferentKeysProduceDifferentSlots(TestContext context) {
        // "foo" and "bar" are known to hash to different slots
        context.assertNotEquals(ClusterSafeMget.getSlot("foo"), ClusterSafeMget.getSlot("bar"));
    }

    @Test
    public void testGetSlotHashTagKeysShareSlot(TestContext context) {
        // Both keys contain hash tag "{user}" → same slot
        int slot1 = ClusterSafeMget.getSlot("{user}.1");
        int slot2 = ClusterSafeMget.getSlot("{user}.2");
        context.assertEquals(slot1, slot2);
    }

    @Test
    public void testGetSlotHashTagMatchesPlainTag(TestContext context) {
        // {user}.anything must hash the same as the plain string "user"
        int slotTagged = ClusterSafeMget.getSlot("{user}.anything");
        int slotPlain  = ClusterSafeMget.getSlot("user");
        context.assertEquals(slotPlain, slotTagged);
    }

    @Test
    public void testGetSlotEmptyHashTagUsesFullKey(TestContext context) {
        // "{}" is an empty hash tag → not treated as a tag; full key is hashed
        int slotEmptyTag = ClusterSafeMget.getSlot("{}foo");
        int slotFullKey  = ClusterSafeMget.getSlot("foo");
        // They must differ because "{}foo" != "foo" and neither uses a hash tag
        context.assertNotEquals(slotEmptyTag, slotFullKey);
    }

    @Test
    public void testGetSlotNoClosingBraceUsesFullKey(TestContext context) {
        // "{foo" has no closing brace → full key is hashed (no hash tag)
        // It must differ from "foo" (which would be the hash-tag content)
        int slotNoClose = ClusterSafeMget.getSlot("{foo");
        int slotPlain   = ClusterSafeMget.getSlot("foo");
        context.assertNotEquals(slotNoClose, slotPlain);
    }

    // -------------------------------------------------------------------------
    // Integration test — requires a real Redis on localhost:6379
    // -------------------------------------------------------------------------

    /**
     * Stores keys in a real Redis instance and verifies that {@link ClusterSafeMget}
     * returns exactly the same values in the same order as a native {@code MGET}.
     *
     * <p>Three keys are chosen so that two of them share a hash tag ({@code {tag}})
     * and therefore land on the same Redis slot, while {@code standalone} lands on a
     * different slot.  This exercises {@link ClusterSafeMget}'s slot-splitting code
     * path (two internal {@code MGET} calls) while still guaranteeing that the result
     * list is reassembled in the original input order.</p>
     *
     * <p>A fourth key ({@code {tag}.missing}) is intentionally <em>not</em> written
     * to Redis so that the {@code null}-for-missing-key behaviour is covered for
     * both the native and the cluster-safe paths.</p>
     *
     * <p>This test is silently skipped when no Redis is reachable (e.g. during a
     * release build without a local Redis), following the project convention
     * established in {@code RedisBasedLockTest}.</p>
     */
    @Test
    public void testResultMatchesNativeMgetIncludingOrder(TestContext context) {
        connectJedis();

        // Verify that the chosen keys actually span two different slots so the test
        // is meaningful (ClusterSafeMget must split the mget into two calls).
        context.assertEquals(
                ClusterSafeMget.getSlot("{tag}.alpha"),
                ClusterSafeMget.getSlot("{tag}.gamma"),
                "Keys with the same hash tag must land on the same slot");
        context.assertNotEquals(
                ClusterSafeMget.getSlot("{tag}.alpha"),
                ClusterSafeMget.getSlot("standalone"),
                "Keys with and without a hash tag must land on different slots");

        // Write three of the four keys; leave {tag}.missing absent on purpose.
        jedis.set("{tag}.alpha",  "value-alpha");
        jedis.set("standalone",   "value-standalone");
        jedis.set("{tag}.gamma",  "value-gamma");

        List<String> keys = Arrays.asList("{tag}.alpha", "standalone", "{tag}.gamma", "{tag}.missing");

        Async async = context.async();

        // Issue the native MGET via the Vert.x async RedisAPI.
        realRedisAPI.mget(keys).onComplete(nativeResult -> {
            context.assertTrue(nativeResult.succeeded(), "Native mget must succeed");
            Response nativeResponse = nativeResult.result();

            // Issue the same lookup through ClusterSafeMget.
            ClusterSafeMget.clusterSafeMget(realRedisAPI, keys).onComplete(clusterResult -> {
                context.assertTrue(clusterResult.succeeded(), "ClusterSafeMget must succeed");
                List<Response> clusterValues = clusterResult.result();

                // Both must return one entry per requested key.
                context.assertEquals(keys.size(), clusterValues.size(),
                        "Result size must equal the number of requested keys");

                // Every position must carry the same string value (or null for missing keys).
                for (int i = 0; i < keys.size(); i++) {
                    String nativeStr  = Objects.toString(nativeResponse.get(i),  null);
                    String clusterStr = Objects.toString(clusterValues.get(i), null);
                    context.assertEquals(nativeStr, clusterStr,
                            "Mismatch at index " + i + " for key '" + keys.get(i) + "'");
                }

                async.complete();
            });
        });
    }
}
