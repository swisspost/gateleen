package org.swisspush.gateleen.logging;

import io.vertx.core.MultiMap;
import org.swisspush.gateleen.core.event.EventBusWriter;
import org.apache.log4j.WriterAppender;
import org.apache.log4j.spi.LoggingEvent;
import io.vertx.core.eventbus.EventBus;

import java.io.Writer;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class EventBusAppender extends WriterAppender {

    private static EventBus eventBus;
    private String address;
    private Writer writer;
    private MultiMap deliveryOptionsHeaders;

    public static void setEventBus(EventBus eventBus) {
        EventBusAppender.eventBus = eventBus;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setDeliveryOptionsHeaders(MultiMap deliveryOptionsHeaders) {
        this.deliveryOptionsHeaders = deliveryOptionsHeaders;
    }

    @Override
    public void append(LoggingEvent event) {
        if(eventBus != null) {
            if(writer == null) {
                writer = new EventBusWriter(eventBus, address, deliveryOptionsHeaders);
                setWriter(writer);
            }
            super.append(event);
        }
    }
}
