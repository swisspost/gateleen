package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import org.swisspush.gateleen.core.lua.LuaScript;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public enum QueueCircuitBreakerLuaScripts implements LuaScript{

    UPDATE_CIRCUIT("circuitbreaker_update.lua"),
    CLOSE_CIRCUIT("circuitbreaker_close.lua"),
    REOPEN_CIRCUIT("circuitbreaker_reopen.lua"),
    HALFOPEN_CIRCUITS("circuitbreaker_halfopen.lua"),
    UNLOCK_SAMPLES("circuitbreaker_unlock_samples.lua");

    private String file;

    QueueCircuitBreakerLuaScripts(String file) { this.file = file; }

    @Override
    public String getFilename() {
        return file;
    }
}
