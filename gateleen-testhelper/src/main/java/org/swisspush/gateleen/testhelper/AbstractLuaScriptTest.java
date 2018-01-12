package org.swisspush.gateleen.testhelper;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Abstract class containing common methods for LuaScript tests
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public abstract class AbstractLuaScriptTest {

    protected Jedis jedis = null;

    @BeforeClass
    public static void checkRedisAvailable(){
        Jedis j = new Jedis("localhost", 6379, 5000);
        try {
            j.flushAll();
        } catch (JedisConnectionException e){
            org.junit.Assume.assumeNoException("Ignoring this test because no running redis is available. This is the case during release", e);
        }
    }

    @Before
    public void connect() {
        jedis = new Jedis("localhost", 6379, 5000);
        jedis.flushAll();
    }

    @After
    public void disconnect() {
        jedis.flushAll();
        jedis.close();
    }

    protected String readScript(String scriptFileName) {
        return readScript(scriptFileName, false);
    }

    protected String readScript(String scriptFileName, boolean stripLogNotice) {
        BufferedReader in = new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream(scriptFileName)));
        StringBuilder sb;
        try {
            sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if (stripLogNotice && line.contains("redis.LOG_NOTICE,")) {
                    continue;
                }
                sb.append(line + "\n");
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
}
