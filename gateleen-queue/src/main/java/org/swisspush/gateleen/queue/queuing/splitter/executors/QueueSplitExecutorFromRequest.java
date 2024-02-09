package org.swisspush.gateleen.queue.queuing.splitter.executors;

import io.vertx.core.http.HttpServerRequest;
import org.swisspush.gateleen.queue.queuing.splitter.QueueSplitterConfiguration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueueSplitExecutorFromRequest extends QueueSplitExecutorBase {
    public QueueSplitExecutorFromRequest(QueueSplitterConfiguration configuration) {
        super(configuration);
    }

    @Override
    public String executeSplit(String queue, HttpServerRequest request) {
        StringBuilder stringBuilder = new StringBuilder(queue);
        if (matches(queue)) {
            if (configuration.getPostfixFromUrl() != null) {
                Matcher matcher = configuration.getPostfixFromUrl().matcher(request.uri());
                if (matcher.matches()) {
                    for (int i = 0; i < matcher.groupCount(); i++) {
                        stringBuilder.append(configuration.getPostfixDelimiter());
                        stringBuilder.append(matcher.group(i + 1));
                    }
                }
            }
            if (configuration.getPostfixFromHeader() != null) {
                String headerValue = request.headers().get(configuration.getPostfixFromHeader());
                if (headerValue != null) {
                    stringBuilder.append(configuration.getPostfixDelimiter());
                    stringBuilder.append(headerValue);
                }
            }
        }
        return stringBuilder.toString();
    }
}
