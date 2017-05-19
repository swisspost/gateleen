package org.swisspush.gateleen.core.lock.lua;

import org.swisspush.gateleen.core.lua.LuaScript;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public enum LockLuaScripts implements LuaScript{

    LOCK_ACQUIRE("lock_acquire.lua"),
    LOCK_RELEASE("lock_release.lua");

    private String file;

    LockLuaScripts(String file) { this.file = file; }

    @Override
    public String getFilename() {
        return file;
    }
}
