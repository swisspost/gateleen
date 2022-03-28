package org.swisspush.gateleen.monitoring;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.impl.types.BulkType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.swisspush.gateleen.core.util.ResourcesUtils;

import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link RedisMonitor} class
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@RunWith(VertxUnitRunner.class)
public class RedisMonitorTest {

    private Vertx vertx;
    private RedisAPI redisAPI;
    private RedisMonitor redisMonitor;
    private MetricsPublisher publisher;

    private final String REDIS_INFO = ResourcesUtils.loadResource("redis_info_output", true);

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        redisAPI = Mockito.mock(RedisAPI.class);
        publisher = Mockito.mock(MetricsPublisher.class);
        redisMonitor = new RedisMonitor(vertx, redisAPI, "main", 1, publisher);
    }

    @Test
    public void testRedisInfoParsing(TestContext testContext) {
        redisMonitor.start();
        Mockito.when(redisAPI.info(any())).thenReturn(Future.succeededFuture(BulkType.create(Buffer.buffer(REDIS_INFO), false)));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> valueCaptor = ArgumentCaptor.forClass(Long.class);

        verify(publisher, timeout(1200).times(58)).publishMetric(keyCaptor.capture(), valueCaptor.capture());

        List<String> keys = keyCaptor.getAllValues();
        testContext.assertTrue(keys.containsAll(List.of("redis_git_sha1", "redis_git_dirty",
                "arch_bits", "process_id", "tcp_port", "uptime_in_seconds", "uptime_in_days", "hz", "lru_clock",
                "connected_clients", "client_longest_output_list", "client_biggest_input_buf", "blocked_clients",
                "used_memory", "used_memory_rss", "used_memory_peak", "used_memory_lua", "mem_fragmentation_ratio", "loading",
                "rdb_changes_since_last_save", "rdb_bgsave_in_progress", "rdb_last_save_time", "rdb_last_bgsave_time_sec",
                "rdb_current_bgsave_time_sec", "aof_enabled", "aof_rewrite_in_progress", "aof_rewrite_scheduled",
                "aof_last_rewrite_time_sec", "aof_current_rewrite_time_sec", "total_connections_received", "total_commands_processed",
                "instantaneous_ops_per_sec", "rejected_connections", "sync_full", "sync_partial_ok", "sync_partial_err",
                "expired_keys", "evicted_keys", "keyspace_hits", "keyspace_misses", "pubsub_channels", "pubsub_patterns",
                "latest_fork_usec", "connected_slaves", "master_repl_offset", "repl_backlog_active", "repl_backlog_size",
                "repl_backlog_first_byte_offset", "repl_backlog_histlen", "used_cpu_sys", "used_cpu_user",
                "used_cpu_sys_children", "used_cpu_user_children", "keyspace.db0.keys", "keyspace.db0.expires", "keyspace.db0.avg_ttl")));

        // assert non numeric entries not published
        testContext.assertFalse(keys.containsAll(List.of("redis_version", "redis_build_id", "redis_mode", "os",
                "multiplexing_api", "gcc_version", "run_id", "executable", "config_file", "used_memory_human",
                "used_memory_peak_human", "mem_allocator", "rdb_last_bgsave_status", "aof_last_bgrewrite_status",
                "aof_last_write_status", "role")));

        // assert some key value pairs
        List<Long> allValues = valueCaptor.getAllValues();
        testContext.assertEquals(170L, allValues.get(keys.indexOf("connected_clients")));
        testContext.assertEquals(1L, allValues.get(keys.indexOf("mem_fragmentation_ratio")));
        testContext.assertEquals(423L, allValues.get(keys.indexOf("total_commands_processed")));
        testContext.assertEquals(1048576L, allValues.get(keys.indexOf("repl_backlog_size")));
        testContext.assertEquals(-1L, allValues.get(keys.indexOf("aof_last_rewrite_time_sec")));
        testContext.assertEquals(36440L, allValues.get(keys.indexOf("used_cpu_sys")));
        testContext.assertEquals(0L, allValues.get(keys.indexOf("used_cpu_sys_children")));
        testContext.assertEquals(2L, allValues.get(keys.indexOf("keyspace.db0.keys")));
        testContext.assertEquals(1L, allValues.get(keys.indexOf("keyspace.db0.expires")));
        testContext.assertEquals(10235L, allValues.get(keys.indexOf("keyspace.db0.avg_ttl")));

    }
}
