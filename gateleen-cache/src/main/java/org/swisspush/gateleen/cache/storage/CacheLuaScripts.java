package org.swisspush.gateleen.cache.storage;

import org.swisspush.gateleen.core.lua.LuaScript;

/**
 * Enum containing the file names of the cache feature related lua scripts.
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public enum CacheLuaScripts implements LuaScript {

    CLEAR_CACHE("clear_cache.lua"),
    CACHE_REQUEST("cache_request.lua");

    private String file;

    CacheLuaScripts(String file) { this.file = file; }

    @Override
    public String getFilename() {
        return file;
    }
}
