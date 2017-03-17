package org.swisspush.gateleen.hook.reducedpropagation.lua;

import org.swisspush.gateleen.core.lua.LuaScript;

/**
 * Enum containing the file names of the reduced propagation feature related lua scripts.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public enum ReducedPropagationLuaScripts implements LuaScript {

    START_QUEUE_TIMER("start_queue_timer.lua"),
    REMOVE_EXPIRED_QUEUES("remove_expired_queues.lua");

    private String file;

    ReducedPropagationLuaScripts(String file) { this.file = file; }

    @Override
    public String getFilename() {
        return file;
    }
}
