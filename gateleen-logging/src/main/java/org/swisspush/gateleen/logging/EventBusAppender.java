package org.swisspush.gateleen.logging;

import io.vertx.core.MultiMap;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.*;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.util.CloseShieldWriter;
import org.swisspush.gateleen.core.event.EventBusWriter;
import io.vertx.core.eventbus.EventBus;

import java.io.Serializable;
import java.io.Writer;

@Plugin(name = "Writer", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public class EventBusAppender extends AbstractWriterAppender {

    /**
     * Builds EventBusAppender instances.
     */
    public static class Builder<B extends EventBusAppender.Builder<B>> extends AbstractAppender.Builder<B>
            implements org.apache.logging.log4j.core.util.Builder<EventBusAppender> {

        private static EventBus eventBus;
        private String address;
        private MultiMap deliveryOptionsHeaders;
        private EventBusWriter.TransmissionMode transmissionMode;

        @Override
        public EventBusAppender build() {
            final Layout<? extends Serializable> layout = getOrCreateLayout();
            if (!(layout instanceof StringLayout)) {
                LOGGER.error("Layout must be a StringLayout to log to ServletContext");
                return null;
            }
            final StringLayout stringLayout = (StringLayout) layout;
            Writer target = new EventBusWriter(eventBus, address, deliveryOptionsHeaders, transmissionMode);
            return new EventBusAppender(getName(), stringLayout, getFilter(), getManager(target, false, stringLayout),
                    isIgnoreExceptions(), getPropertyArray());
        }

        public static void setEventBus(EventBus eventBus) {
            EventBusAppender.Builder.eventBus = eventBus;
        }

        public B setAddress(String address) {
            this.address = address;
            return asBuilder();
        }

        public B setDeliveryOptionsHeaders(MultiMap deliveryOptionsHeaders) {
            this.deliveryOptionsHeaders = deliveryOptionsHeaders;
            return asBuilder();
        }

        public B setTransmissionMode(EventBusWriter.TransmissionMode transmissionMode) {
            this.transmissionMode = transmissionMode;
            return asBuilder();
        }
    }

    /**
     * Holds data to pass to factory method.
     */
    private static class FactoryData {
        private final StringLayout layout;
        private final String name;
        private final Writer writer;

        /**
         * Builds instances.
         *
         * @param writer The OutputStream.
         * @param type   The name of the target.
         * @param layout A String layout
         */
        public FactoryData(final Writer writer, final String type, final StringLayout layout) {
            this.writer = writer;
            this.name = type;
            this.layout = layout;
        }
    }

    private static class WriterManagerFactory implements ManagerFactory<WriterManager, EventBusAppender.FactoryData> {

        /**
         * Creates a WriterManager.
         *
         * @param name The name of the entity to manage.
         * @param data The data required to create the entity.
         * @return The WriterManager
         */
        @Override
        public WriterManager createManager(final String name, final EventBusAppender.FactoryData data) {
            return new WriterManager(data.writer, data.name, data.layout, true);
        }
    }

    private static EventBusAppender.WriterManagerFactory factory = new EventBusAppender.WriterManagerFactory();

    @PluginFactory
    public static EventBusAppender createAppender(StringLayout layout, final Filter filter, final String name,
                                                  final EventBus eventBus, final String address,
                                                  final MultiMap deliveryOptionsHeaders, EventBusWriter.TransmissionMode transmissionMode,
                                                  final boolean follow, final boolean ignore) {
        if (name == null) {
            LOGGER.error("No name provided for EventBusAppender");
            return null;
        }
        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }
        Writer target = new EventBusWriter(eventBus, address, deliveryOptionsHeaders, transmissionMode);
        return new EventBusAppender(name, layout, filter, getManager(target, follow, layout), ignore, null);
    }

    private static WriterManager getManager(final Writer target, final boolean follow, final StringLayout layout) {
        final Writer writer = new CloseShieldWriter(target);
        final String managerName = target.getClass().getName() + "@" + Integer.toHexString(target.hashCode()) + '.'
                + follow;
        return WriterManager.getManager(managerName, new EventBusAppender.FactoryData(writer, managerName, layout), factory);
    }

    @PluginBuilderFactory
    public static <B extends EventBusAppender.Builder<B>> B newBuilder() {
        return new EventBusAppender.Builder<B>().asBuilder();
    }

    private EventBusAppender(final String name, final StringLayout layout, final Filter filter,
                             final WriterManager manager, final boolean ignoreExceptions, final Property[] properties) {
        super(name, layout, filter, ignoreExceptions, true, properties, manager);
    }
}
