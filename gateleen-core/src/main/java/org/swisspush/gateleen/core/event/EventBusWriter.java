package org.swisspush.gateleen.core.event;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;

/**
 * A writer that publishes to the event bus.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class EventBusWriter extends Writer {

    public enum TransmissionMode {
        publish, send;

        public static TransmissionMode fromString(String mode){
            for (TransmissionMode transmissionMode : values()) {
                if(transmissionMode.name().equalsIgnoreCase(mode)){
                    return transmissionMode;
                }
            }
            return TransmissionMode.publish;
        }
    }

    private StringBuffer buffer;
    private EventBus eventBus;
    private String address;
    private MultiMap deliveryOptionsHeaders;
    private TransmissionMode transmissionMode;

    private Logger log = LoggerFactory.getLogger(EventBusWriter.class);

    public EventBusWriter(EventBus eventBus, String address, MultiMap deliveryOptionsHeaders, TransmissionMode transmissionMode) {
        this.eventBus = eventBus;
        this.address = address;
        this.deliveryOptionsHeaders = deliveryOptionsHeaders;
        this.transmissionMode = transmissionMode;
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

            if(TransmissionMode.send == transmissionMode){
                eventBus.send(address, buffer.toString(), options, new Handler<AsyncResult<Message<JsonObject>>>() {
                    @Override
                    public void handle(AsyncResult<Message<JsonObject>> reply) {
                        if (reply.succeeded() && "ok".equals(reply.result().body().getString("status"))) {
                            log.info("Successfully sent to (and got reply from) eventBus address " + address);
                        } else {
                            log.error("Failed to send (not publish) to the eventBus: " + reply.cause());
                        }
                    }
                });
            } else {
                eventBus.publish(address, buffer.toString(), options);
            }
            buffer = null;
        }
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
