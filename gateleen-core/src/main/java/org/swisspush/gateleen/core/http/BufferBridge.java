package org.swisspush.gateleen.core.http;

import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Support class bridging a buffer writer and dataHandler / endHandler pair.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class BufferBridge {
    private Handler<Buffer> dataHandler;
    private Handler<Void> endHandler;
    private Handler<Buffer> bodyHandler;
    private Handler<Throwable> exceptionHandler;
    private Queue<Buffer> queue = new LinkedList<>();
    private Buffer body = Buffer.buffer();
    private boolean ended = false;
    private Vertx vertx;
    private final Logger log = LoggerFactory.getLogger(BufferBridge.class);

    public BufferBridge(Vertx vertx) {
        this.vertx = vertx;
    }

    protected void pump() {
        vertx.runOnContext(event -> {
            try {
                if (!queue.isEmpty()) {
                    log.trace("Pumping from queue");
                    dataHandler.handle(queue.poll());
                    pump();
                } else {
                    if (ended && endHandler != null) {
                        log.trace("Ending from pump");
                        endHandler.handle(null);
                    }
                }
            } catch (Exception e) {
                if (exceptionHandler != null) {
                    exceptionHandler.handle(e);
                }
            }
        });
    }

    protected void doWrite(Buffer chunk) {
        body.appendBuffer(chunk);
        if (dataHandler != null && queue.isEmpty()) {
            log.trace("Writing directly to handler");
            try {
                dataHandler.handle(chunk);
            } catch (Exception e) {
                if (exceptionHandler != null) {
                    exceptionHandler.handle(e);
                }
            }
        } else {
            log.trace("Writing to queue");
            queue.offer(chunk);
        }
    }

    protected Future<Void> doEnd() {
        ended = true;
        if (bodyHandler != null) {
            bodyHandler.handle(body);
        }
        if (endHandler != null && queue.isEmpty()) {
            log.trace("Ending handler directly");
            try {
                endHandler.handle(null);
            } catch (Exception e) {
                if (exceptionHandler != null) {
                    exceptionHandler.handle(e);
                }
            }
            endHandler = null;
            dataHandler = null;
        }
        return Future.succeededFuture();
    }

    public void setDataHandler(Handler<Buffer> dataHandler) {
        this.dataHandler = dataHandler;
    }

    public void setExceptionHandler(Handler<Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    public void setEndHandler(Handler<Void> endHandler) {
        this.endHandler = endHandler;
    }

    public void setBodyHandler(Handler<Buffer> bodyHandler) {
        this.bodyHandler = bodyHandler;
    }
}
