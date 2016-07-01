package org.swisspush.gateleen.core.lua;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class RedisCommandDoNothing implements RedisCommand{

    @Override
    public void exec(int executionCounter) {
        // do nothing here
    }
}
