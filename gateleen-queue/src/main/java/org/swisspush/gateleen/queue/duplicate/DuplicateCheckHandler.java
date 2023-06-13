package org.swisspush.gateleen.queue.duplicate;

import com.google.common.collect.ImmutableList;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.redis.RedisProvider;
import org.swisspush.gateleen.core.util.HashCodeGenerator;

import java.util.Objects;

/**
 * Class which is responsible for checking wheter a request is duplicate or not
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public final class DuplicateCheckHandler {

    private static final String REDIS_KEY_TEMPLATE = "{{history:%s-%s}}";
    private static final String DEFAULT_REDIS_ENTRY_VALUE = "1";
    private static final int DEFAULT_TTL = 60;

    private static Logger log = LoggerFactory.getLogger(DuplicateCheckHandler.class);

    private DuplicateCheckHandler() {
        // Prevent instantiation
    }

    /**
     * This method checks if an entry for the provided information (uri and buffer) is stored in the redis database. When no entry was found
     * in the database, a new entry will be saved to the database using a key which is created from the given parameters (uri and buffer).
     * The new entry expires after ttl has expired.
     *
     * @param redisProvider provider for redis
     * @param uri      the request uri
     * @param buffer   the request payload
     * @param ttl      the timeToLive (in seconds) for the storage entry
     * @param callback the result callback. Returns true if the request is a duplicate else returns false
     */
    public static void checkDuplicateRequest(RedisProvider redisProvider, String uri, Buffer buffer, String ttl, Handler<Boolean> callback) {
        Integer timeToLive = parseTimeToLive(ttl);
        String redisKey = getRedisKey(uri, HashCodeGenerator.createHashCode(uri, buffer.toString()));
        handleStorage(redisProvider, redisKey, timeToLive, callback);
    }

    private static Integer parseTimeToLive(String ttl) {
        int timeToLive;
        try {
            timeToLive = Integer.parseInt(ttl);
        } catch (NumberFormatException e) {
            timeToLive = DEFAULT_TTL;
            log.error("Can't parse value '{}' for time to live. Should be a number. Using default value '{}'", ttl, DEFAULT_TTL);
        }
        return timeToLive;
    }

    private static String getRedisKey(String uri, String hash) {
        return String.format(REDIS_KEY_TEMPLATE, uri, hash);
    }

    private static void handleStorage(RedisProvider redisProvider, final String redisKey, int ttl, final Handler<Boolean> callback) {

        // read from storage
        redisProvider.redis().onSuccess(redisAPI -> redisAPI.get(redisKey, reply -> {
            if (reply.failed()) {
                log.error("get command for redisKey '{}' resulted in cause {}", redisKey, logCause(reply));
                return;
            }

            if (!DEFAULT_REDIS_ENTRY_VALUE.equals(Objects.toString(reply.result(), ""))) {
                // save to storage
                redisAPI.setnx(redisKey, DEFAULT_REDIS_ENTRY_VALUE, setnxReply -> {
                    if (setnxReply.failed()) {
                        log.error("set command for redisKey '{}' resulted in cause {}", redisKey, logCause(setnxReply));
                        return;
                    }

                    // set expire
                    redisAPI.expire(ImmutableList.of(redisKey, String.valueOf(ttl)), expireReply -> {
                        if (expireReply.failed()) {
                            log.error("expire command for redisKey '{}' resulted in cause {}", redisKey, logCause(expireReply));
                        }
                    });
                });
                callback.handle(Boolean.FALSE);
            } else {
                log.info("received a duplicate request for redisKey: {}", redisKey);
                callback.handle(Boolean.TRUE);
            }
        })).onFailure(throwable -> log.error("Redis: get command for redisKey '{}' resulted in cause {}", redisKey, throwable.getMessage()));
    }

    private static String logCause(AsyncResult result) {
        if (result.cause() != null) {
            return result.cause().getMessage();
        }
        return null;
    }
}
