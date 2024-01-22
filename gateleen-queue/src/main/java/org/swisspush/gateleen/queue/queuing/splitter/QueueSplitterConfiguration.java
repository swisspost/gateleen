package org.swisspush.gateleen.queue.queuing.splitter;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Container holding configuration values for {@link QueueSplitterHandler} identified
 * by a queue pattern.
 *
 * @author https://github.com/gcastaldi [Giannandrea Castaldi]
 */
public class QueueSplitterConfiguration {

    private final Pattern queue;

    private final String postfixDelimiter;

    @Nullable
    private final List<String> postfixFromStatic;

    @Nullable
    private final String postfixFromHeader;

    @Nullable
    private final String postfixFromUrl;


    public QueueSplitterConfiguration(
            Pattern queue,
            String postfixDelimiter,
            @Nullable List<String> postfixFromStatic,
            @Nullable String postfixFromHeader,
            @Nullable String postfixFromUrl) {
        this.queue = queue;
        this.postfixDelimiter = postfixDelimiter;
        this.postfixFromStatic = postfixFromStatic;
        this.postfixFromHeader = postfixFromHeader;
        this.postfixFromUrl = postfixFromUrl;
    }

    public Pattern getQueue() {
        return queue;
    }

    public String getPostfixDelimiter() {
        return postfixDelimiter;
    }

    @Nullable
    public List<String> getPostfixFromStatic() {
        return postfixFromStatic;
    }

    @Nullable
    public String getPostfixFromHeader() {
        return postfixFromHeader;
    }

    @Nullable
    public String getPostfixFromUrl() {
        return postfixFromUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueueSplitterConfiguration that = (QueueSplitterConfiguration) o;
        return Objects.equals(queue, that.queue) &&
                Objects.equals(postfixDelimiter, that.postfixDelimiter) &&
                Objects.equals(postfixFromStatic, that.postfixFromStatic) &&
                Objects.equals(postfixFromHeader, that.postfixFromHeader) &&
                Objects.equals(postfixFromUrl, that.postfixFromUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(queue, postfixDelimiter, postfixFromStatic, postfixFromHeader, postfixFromUrl);
    }

    @Override
    public String toString() {
        return "QueueSplitterConfiguration{" +
                "queue=" + queue +
                ", postfixDelimiter='" + postfixDelimiter + '\'' +
                ", postfixFromStatic=" + postfixFromStatic +
                ", postfixFromHeader='" + postfixFromHeader + '\'' +
                ", postfixFromUrl='" + postfixFromUrl + '\'' +
                '}';
    }
}
