package org.swisspush.gateleen.routing;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.streams.Pump;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

/**
 * This is mainly a copy of io.vertx.core.streams.impl.PumpImpl
 * with an 'auto-resurrection' (i.e. timed resuming) of a paused readStream
 */
public class ResurrectingPump<T> implements Pump {
    private final ReadStream<T> readStream;
    private final WriteStream<T> writeStream;
    private final Handler<T> dataHandler;
    private final Handler<Void> drainHandler;
    private int pumped;

    /**
     * Create a new {@code Pump} with the given {@code ReadStream} and {@code WriteStream}. Set the write queue max size
     * of the write stream to {@code maxWriteQueueSize}
     */
    ResurrectingPump(Vertx vertx, ReadStream<T> rs, WriteStream<T> ws, int maxWriteQueueSize) {
      this(vertx, rs, ws);
      this.writeStream.setWriteQueueMaxSize(maxWriteQueueSize);
    }

    ResurrectingPump(Vertx vertx, ReadStream<T> rs, WriteStream<T> ws) {
      this.readStream = rs;
      this.writeStream = ws;
      drainHandler = v-> readStream.resume();
      dataHandler = data -> {
        writeStream.write(data);
        incPumped();
        if (writeStream.writeQueueFull()) {
          readStream.pause();
          writeStream.drainHandler(drainHandler);
            // ugly hack - but I find no better solution:
            //
            // unconditional resume() the (paused) readStream for cases when the writeStream's drainHandler is not invoked
            // this happens if writeStream is a HttpServerResponse and meanwhile this response is closed from the other side
            // this leads to a 'semi paused' socket if readStream is an incoming stream from a HttpClient.
            // see also https://github.com/eclipse/vert.x/issues/2065
            vertx.setTimer(5000, id -> {
                readStream.resume();
            });
        }
      };
    }

    /**
     * Set the write queue max size to {@code maxSize}
     */
    @Override
    public ResurrectingPump setWriteQueueMaxSize(int maxSize) {
      writeStream.setWriteQueueMaxSize(maxSize);
      return this;
    }

    /**
     * Start the Pump. The Pump can be started and stopped multiple times.
     */
    @Override
    public ResurrectingPump start() {
      readStream.handler(dataHandler);
      return this;
    }

    /**
     * Stop the Pump. The Pump can be started and stopped multiple times.
     */
    @Override
    public ResurrectingPump stop() {
      writeStream.drainHandler(null);
      readStream.handler(null);
      return this;
    }

    /**
     * Return the total number of elements pumped by this pump.
     */
    @Override
    public synchronized int numberPumped() {
      return pumped;
    }

    // Note we synchronize as numberPumped can be called from a different thread however incPumped will always
    // be called from the same thread so we benefit from bias locked optimisation which should give a very low
    // overhead
    private synchronized void incPumped() {
      pumped++;
    }

}
