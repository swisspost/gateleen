package org.swisspush.gateleen.core.lua;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.RedisClient;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Created by webermarca on 01.07.2016.
 */
public class LuaScriptState {
    private LuaScript luaScriptType;
    /** the script itself */
    private String script;
    /** if the script logs to the redis log */
    private boolean logoutput = false;
    /** the sha, over which the script can be accessed in redis */
    private String sha;

    private RedisClient redisClient;

    private Logger log = LoggerFactory.getLogger(LuaScriptState.class);

    public LuaScriptState(LuaScript luaScriptType, RedisClient redisClient, boolean logoutput) {
        this.luaScriptType = luaScriptType;
        this.redisClient = redisClient;
        this.logoutput = logoutput;
        this.composeLuaScript(luaScriptType);
        this.loadLuaScript(new RedisCommandDoNothing(), 0);
    }

    /**
     * Reads the script from the classpath and removes logging output if logoutput is false.
     * The script is stored in the class member script.
     *
     * @param luaScriptType
     */
    private void composeLuaScript(LuaScript luaScriptType) {
        log.info("read the lua script for script type: " + luaScriptType + " with logoutput: " + logoutput);
        this.script = readLuaScriptFromClasspath(luaScriptType);
        this.sha = DigestUtils.sha1Hex(this.script);
    }

    private String readLuaScriptFromClasspath(LuaScript luaScriptType) {
        BufferedReader in = new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream(luaScriptType.getFilename())));
        StringBuilder sb;
        try {
            sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if (!logoutput && line.contains("redis.log(redis.LOG_NOTICE,")) {
                    continue;
                }
                sb.append(line).append("\n");
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        return sb.toString();
    }

    /**
     * Load the get script into redis and store the sha in the class member sha.
     *
     * @param redisCommand the redis command that should be executed, after the script is loaded.
     * @param executionCounter a counter to control recursion depth
     */
    public void loadLuaScript(final RedisCommand redisCommand, int executionCounter) {
        final int executionCounterIncr = ++executionCounter;

        // check first if the lua script already exists in the store
        redisClient.scriptExists(this.sha, resultArray -> {
            if(resultArray.failed()){
                log.error("Error checking whether lua script exists", resultArray.cause());
                return;
            }
            Long exists = resultArray.result().getLong(0);
            // if script already
            if(Long.valueOf(1).equals(exists)) {
                log.debug("RedisStorage script already exists in redis cache: " + luaScriptType);
                redisCommand.exec(executionCounterIncr);
            } else {
                log.info("load lua script for script type: " + luaScriptType + " logutput: " + logoutput);
                redisClient.scriptLoad(script, stringAsyncResult -> {
                    String newSha = stringAsyncResult.result();
                    log.info("got sha from redis for lua script: " + luaScriptType + ": " + newSha);
                    if(!newSha.equals(sha)) {
                        log.warn("the sha calculated by myself: " + sha + " doesn't match with the sha from redis: " + newSha + ". We use the sha from redis");
                    }
                    sha = newSha;
                    log.info("execute redis command for script type: " + luaScriptType + " with new sha: " + sha);
                    redisCommand.exec(executionCounterIncr);
                });
            }
        });
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public boolean getLogoutput() {
        return logoutput;
    }

    public void setLogoutput(boolean logoutput) {
        this.logoutput = logoutput;
    }

    public String getSha() {
        return sha;
    }

    public void setSha(String sha) {
        this.sha = sha;
    }

}
