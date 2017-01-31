package org.swisspush.gateleen.merge;

import io.vertx.core.buffer.Buffer;

/**
 * Represents a sub node of the original request.
 * Holds the information whether the requested
 * resource is a collection, a resource or either
 * found.
 *
 *
 *
 * @author https://github.com/ljucam [Mario Aerni]
 */
public class MergeData {
    private final boolean targetCollection;
    private final Buffer content;
    private final int statusCode;
    private final String statusMessage;
    private final String targetRequest;

    public MergeData(final Buffer content, final int statusCode,
                     final String statusMessage, final boolean targetCollection,
                     final String targetRequest) {
        this.targetCollection = targetCollection;
        this.content = content;
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.targetRequest = targetRequest;
    }

    public boolean isTargetCollection() {
        return targetCollection;
    }

    public Buffer getContent() {
        return content;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public String getTargetRequest() {
        return targetRequest;
    }
}
