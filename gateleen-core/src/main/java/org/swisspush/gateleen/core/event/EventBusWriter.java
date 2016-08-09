package org.swisspush.gateleen.core.event;

import io.vertx.core.MultiMap;
import io.vertx.core.eventbus.DeliveryOptions;
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
    private MultiMap deliveryOptionsHeaders;

    public EventBusWriter(EventBus eventBus, String address, MultiMap deliveryOptionsHeaders) {
        this.eventBus = eventBus;
        this.address = address;
        this.deliveryOptionsHeaders = deliveryOptionsHeaders;
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
            DeliveryOptions options = new DeliveryOptions();
            if(deliveryOptionsHeaders != null){
                options.setHeaders(deliveryOptionsHeaders);
            }
            eventBus.publish(address, buffer.toString(), options);
            buffer = null;
        }
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
