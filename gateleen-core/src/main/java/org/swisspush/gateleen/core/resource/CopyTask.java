package org.swisspush.gateleen.core.resource;

import io.vertx.core.MultiMap;

/**
 * Represents a copy task.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class CopyTask {
    private String sourceUri;
    private String destinationUri;
    private MultiMap headers;
    private MultiMap staticHeaders;

    public CopyTask(String sourceUri, String destinationUri, MultiMap headers, MultiMap staticHeaders) {
        this.sourceUri = sourceUri;
        this.destinationUri = destinationUri;
        this.headers = headers;
        this.staticHeaders = staticHeaders;
    }

    public String getSourceUri() {
        return sourceUri;
    }

    public String getDestinationUri() {
        return destinationUri;
    }

    public MultiMap getHeaders() {
        return headers;
    }

    public MultiMap getStaticHeaders() {
        return staticHeaders;
    }

}
