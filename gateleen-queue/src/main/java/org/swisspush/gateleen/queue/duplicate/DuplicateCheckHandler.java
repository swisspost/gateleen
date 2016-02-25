package org.swisspush.gateleen.queue.duplicate;

import org.swisspush.gateleen.core.util.HashCodeGenerator;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * @param redisClient redisClient
     * @param uri the request uri
     * @param buffer the request payload
     * @param ttl the timeToLive (in seconds) for the storage entry
     * @param callback the result callback. Returns true if the request is a duplicate else returns false
     */
    public static void checkDuplicateRequest(RedisClient redisClient, String uri, Buffer buffer, String ttl, Handler<Boolean> callback) {
        Integer timeToLive = parseTimeToLive(ttl);
        String redisKey = getRedisKey(uri, HashCodeGenerator.createHashCode(uri, buffer.toString()));
        handleStorage(redisClient, redisKey, timeToLive, callback);
    }

    private static Integer parseTimeToLive(String ttl) {
        Integer timeToLive;
        try {
            timeToLive = Integer.parseInt(ttl);
        } catch (NumberFormatException e) {
            timeToLive = DEFAULT_TTL;
            log.error("Can't parse value '" + ttl + "' for time to live. Should be a number. Using default value '" + DEFAULT_TTL + "'");
        }
        return timeToLive;
    }

    private static String getRedisKey(String uri, String hash) {
        return String.format(REDIS_KEY_TEMPLATE, uri, hash);
    }

    private static void handleStorage(RedisClient redisClient, final String redisKey, int ttl, final Handler<Boolean> callback) {

        // read from storage
        redisClient.get(redisKey, reply -> {
            if(reply.failed()){
                log.error("get command for redisKey '" + redisKey + "' resulted in cause " + logCause(reply));
                return;
            }

            if (!DEFAULT_REDIS_ENTRY_VALUE.equals(reply.result())) {
                // save to storage
                redisClient.setnx(redisKey, DEFAULT_REDIS_ENTRY_VALUE, setnxReply -> {
                    if(setnxReply.failed()){
                        log.error("set command for redisKey '" + redisKey + "' resulted in cause " + logCause(setnxReply));
                        return;
                    }

                    // set expire
                    redisClient.expire(redisKey, ttl, expireReply -> {
                        if(expireReply.failed()){
                            log.error("expire command for redisKey '" + redisKey + "' resulted in cause " + logCause(expireReply));
                        }
                    });
                });
                callback.handle(Boolean.FALSE);
            } else {
                log.info("received a duplicate request for redisKey: " + redisKey);
                callback.handle(Boolean.TRUE);
            }
        });
    }

    private static String logCause(AsyncResult result){
        if(result.cause() != null){
            return result.cause().getMessage();
        }
        return null;
    }
}
