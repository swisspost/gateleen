package org.swisspush.gateleen.core.event;

import io.vertx.core.eventbus.EventBus;

import java.io.IOException;
import java.io.Writer;

/**
 * A writer that publishes to the event bus.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class EventBusWriter extends Writer {

    private StringBuffer buffer;
    private EventBus eventBus;
    private String address;

    public EventBusWriter(EventBus eventBus, String address) {
        this.eventBus = eventBus;
        this.address = address;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        if(buffer == null) {
            buffer = new StringBuffer();
        }
        buffer.append(cbuf, off, len);
    }

    @Override
    public void flush() throws IOException {
        if(buffer != null) {
            eventBus.publish(address, buffer.toString());
            buffer = null;
        }
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
