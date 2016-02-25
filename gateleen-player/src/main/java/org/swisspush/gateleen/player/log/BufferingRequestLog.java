package org.swisspush.gateleen.player.log;

import org.swisspush.gateleen.player.exchange.Exchange;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A request log implemented with an in-memory buffer. Thread safe.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class BufferingRequestLog extends RequestLog {

    Queue<Exchange> buffer = new ConcurrentLinkedQueue<>();

    public void add(Exchange exchange) {
        buffer.add(exchange);
    }

    /**
     * Move all exchange from this request log to another one.
     * 
     * @param target target
     */
    public void dump(BufferingRequestLog target) {
        while (!buffer.isEmpty()) {
            target.add(buffer.poll());
        }
    }

    public void clear() {
        buffer.clear();
    }

    @Override
    public Iterator<Exchange> iterator() {
        return buffer.iterator();
    }

    @Override
    public String toString() {
        StringBuffer result = new StringBuffer();
        for (Exchange exchange : this) {
            result.append(exchange);
            result.append("\n");
        }
        return result.toString();
    }
}
