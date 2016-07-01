package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import org.swisspush.gateleen.core.lua.LuaScript;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public enum QueueCircuitBreakerLuaScripts implements LuaScript{

    UPDATE_STATS("update_circuitbreaker_stats.lua");

    private String file;

    QueueCircuitBreakerLuaScripts(String file) { this.file = file; }

    @Override
    public String getFilename() {
        return file;
    }
}
