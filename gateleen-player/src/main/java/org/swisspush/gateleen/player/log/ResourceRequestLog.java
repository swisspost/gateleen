package org.swisspush.gateleen.player.log;

import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * Request log implementation fetching a request log resource containing JSON lines as written by the log filter.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class ResourceRequestLog extends ReaderRequestLog {

    private String location;

    /**
     * @param urlPrefix an absolute URL prefix to prepend to all request URL.
     * @param location a resource location following Spring's conventions (file path, URL, classpath:, ...).
     */
    public ResourceRequestLog(String urlPrefix, String location) {
        super(urlPrefix);
        this.location = location;
    }

    protected Reader getReader() {
        try {
            return new InputStreamReader(new DefaultResourceLoader().getResource(location).getInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
