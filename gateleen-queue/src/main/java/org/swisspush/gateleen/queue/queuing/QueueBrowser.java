package org.swisspush.gateleen.queue.queuing;

import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.core.util.StringUtils;
import io.vertx.core.AsyncResult;
import io.vertx.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.Charset;
import java.util.*;

import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 * @deprecated Use http api from vertx-redisques (version greater than v2.2.4) directly. See https://github.com/swisspush/vertx-redisques
 */
public class QueueBrowser implements Handler<HttpServerRequest> {

    public static final String APPLICATION_JSON = "application/json";
    public static final String CONTENT_TYPE = "content-type";
    public static final String UTF_8 = "UTF-8";
    public static final String PAYLOAD = "payload";
    private static Logger log = LoggerFactory.getLogger(QueueBrowser.class);
    private static final int DEFAULT_QUEUE_NUM = 1000;
    private static final int DEFAULT_MAX_QUEUEITEM_COUNT = 49;
    private static final String SHOW_EMPTY_QUEUES_PARAM = "showEmptyQueues";
    private EventBus eb;
    private final String redisquesAddress;

    private Router router;

    public QueueBrowser(Vertx vertx, String prefix, final String redisquesAddress, final MonitoringHandler monitoringHandler) {
        this.router = Router.router(vertx);
        this.redisquesAddress = redisquesAddress;
        eb = vertx.eventBus();

        // List queuing features
        router.get(prefix + "/").handler(ctx -> {
            JsonObject result = new JsonObject();
            JsonArray items = new JsonArray();
            items.add("locks/");
            items.add("monitoring");
            items.add("queues/");
            result.put(lastPart(ctx.request().path(), "/"), items);
            ctx.response().putHeader(CONTENT_TYPE, APPLICATION_JSON);
            ctx.response().end(result.encode());
        });

        // List queues
        router.get(prefix + "/queues/").handler(ctx -> monitoringHandler.updateQueuesSizesInformation(DEFAULT_QUEUE_NUM, false, new MonitoringHandler.MonitoringCallback() {
            @Override
            public void onDone(JsonObject result) {
                JsonArray array = result.getJsonArray("queues");
                JsonArray resultArray = new JsonArray();
                for (int i = 0; i < array.size(); i++) {
                    JsonObject arrayEntry = array.getJsonObject(i);
                    resultArray.add(arrayEntry.getString("name"));
                }
                result.put(lastPart(ctx.request().path(), "/"), resultArray);
                jsonResponse(ctx.response(), result);
            }

            @Override
            public void onFail(String errorMessage, int statusCode) {
                ctx.response().setStatusCode(statusCode);
                ctx.response().setStatusMessage(errorMessage);
                ctx.response().end();
            }
        }));

        // List queue items
        router.getWithRegex(prefix + "/queues/[^/]+").handler(ctx -> {
            final String queue = lastPart(ctx.request().path(), "/");
            String limitParam = null;
            if (ctx.request() != null && ctx.request().params().contains("limit")) {
                limitParam = ctx.request().params().get("limit");
            }
            eb.request(redisquesAddress, buildGetQueueItemsOperation(queue, limitParam), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                JsonObject replyBody = reply.result().body();
                if (OK.equals(replyBody.getString(STATUS))) {
                    List<Object> list = reply.result().body().getJsonArray(VALUE).getList();
                    JsonArray items = new JsonArray();
                    for (Object item : list.toArray()) {
                        items.add((String) item);
                    }
                    JsonObject result = new JsonObject().put(queue, items);
                    jsonResponse(ctx.response(), result);
                } else {
                    ctx.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                    ctx.response().end(reply.result().body().getString("message"));
                    log.warn("Error in routerMatcher.getWithRegEx. Command = '" + (replyBody.getString("command") == null ? "<null>" : replyBody.getString("command")) + "'.");
                }
            });
        });

        // Delete all queue items
        router.deleteWithRegex(prefix + "/queues/[^/]+").handler(ctx -> {
            final String queue = lastPart(ctx.request().path(), "/");
            eb.request(redisquesAddress, buildDeleteAllQueueItemsOperation(queue), reply -> ctx.response().end());
        });

        // Get item
        router.getWithRegex(prefix + "/queues/([^/]+)/[0-9]+").handler(ctx -> {
            final String queue = lastPart(ctx.request().path().substring(0, ctx.request().path().length() - 2), "/");
            final int index = Integer.parseInt(lastPart(ctx.request().path(), "/"));
            eb.request(redisquesAddress, buildGetQueueItemOperation(queue, index), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                JsonObject replyBody = reply.result().body();
                if (OK.equals(replyBody.getString(STATUS))) {
                    ctx.response().putHeader(CONTENT_TYPE, APPLICATION_JSON);
                    ctx.response().end(decode(reply.result().body().getString(VALUE)));
                } else {
                    ctx.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                    ctx.response().setStatusMessage(StatusCode.NOT_FOUND.getStatusMessage());
                    ctx.response().end("Not Found");
                }
            });
        });

        // Replace item
        router.putWithRegex(prefix + "/queues/([^/]+)/[0-9]+").handler(ctx -> {
            final String queue = part(ctx.request().path(), "/", 2);
            checkLocked(queue, ctx.request(), aVoid -> {
                final int index = Integer.parseInt(lastPart(ctx.request().path(), "/"));
                ctx.request().bodyHandler(buffer -> {
                    String strBuffer = encode(buffer.toString());
                    eb.request(redisquesAddress, buildReplaceQueueItemOperation(queue, index, strBuffer),
                            (Handler<AsyncResult<Message<JsonObject>>>) reply -> checkReply(reply.result(), ctx.request(), StatusCode.NOT_FOUND));
                });
            });
        });

        // Delete item
        router.deleteWithRegex(prefix + "/queues/([^/]+)/[0-9]+").handler(ctx -> {
            final String queue = part(ctx.request().path(), "/", 2);
            final int index = Integer.parseInt(lastPart(ctx.request().path(), "/"));
            checkLocked(queue, ctx.request(), aVoid -> eb.request(redisquesAddress, buildDeleteQueueItemOperation(queue, index),
                    (Handler<AsyncResult<Message<JsonObject>>>) reply -> checkReply(reply.result(), ctx.request(), StatusCode.NOT_FOUND)));
        });

        // Add item
        router.postWithRegex(prefix + "/queues/([^/]+)/").handler(ctx -> {
            final String queue = part(ctx.request().path(), "/", 1);
            ctx.request().bodyHandler(buffer -> {
                String strBuffer = encode(buffer.toString());
                eb.request(redisquesAddress, buildAddQueueItemOperation(queue, strBuffer),
                        (Handler<AsyncResult<Message<JsonObject>>>) reply -> checkReply(reply.result(), ctx.request(), StatusCode.BAD_REQUEST));
            });
        });

        // get all locks
        router.getWithRegex(prefix + "/locks/").handler(ctx -> eb.request(redisquesAddress, buildGetAllLocksOperation(),
                (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                    if (OK.equals(reply.result().body().getString(STATUS))) {
                        jsonResponse(ctx.response(), reply.result().body().getJsonObject(VALUE));
                    } else {
                        ctx.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                        ctx.response().setStatusMessage(StatusCode.NOT_FOUND.getStatusMessage());
                        ctx.response().end("Not Found");
                    }
                }));

        // add lock
        router.putWithRegex(prefix + "/locks/[^/]+").handler(ctx -> {
            String queue = lastPart(ctx.request().path(), "/");
            eb.request(redisquesAddress, buildPutLockOperation(queue, extractUser(ctx.request())),
                    (Handler<AsyncResult<Message<JsonObject>>>) reply -> checkReply(reply.result(), ctx.request(), StatusCode.BAD_REQUEST));
        });

        // get single lock
        router.getWithRegex(prefix + "/locks/[^/]+").handler(ctx -> {
            String queue = lastPart(ctx.request().path(), "/");
            eb.request(redisquesAddress, buildGetLockOperation(queue), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
                if (OK.equals(reply.result().body().getString(STATUS))) {
                    ctx.response().putHeader(CONTENT_TYPE, APPLICATION_JSON);
                    ctx.response().end(reply.result().body().getString(VALUE));
                } else {
                    ctx.response().setStatusCode(StatusCode.NOT_FOUND.getStatusCode());
                    ctx.response().setStatusMessage(StatusCode.NOT_FOUND.getStatusMessage());
                    ctx.response().end(NO_SUCH_LOCK);
                }
            });
        });

        // delete single lock
        router.deleteWithRegex(prefix + "/locks/[^/]+").handler(ctx -> {
            String queue = lastPart(ctx.request().path(), "/");
            eb.request(redisquesAddress, buildDeleteLockOperation(queue),
                    (Handler<AsyncResult<Message<JsonObject>>>) reply -> checkReply(reply.result(), ctx.request(), StatusCode.BAD_REQUEST));
        });

        // Gathering queues monitoring informations
        router.getWithRegex(prefix + "/monitoring/[^/]*").handler(ctx -> {
            int numQueues = extractNumOfQueuesValue(ctx.request().path(), "/");
            boolean showEmptyQueues = showEmptyQueues(ctx.request().params());
            monitoringHandler.updateQueuesSizesInformation(numQueues, showEmptyQueues, new MonitoringHandler.MonitoringCallback() {
                @Override
                public void onDone(JsonObject result) {
                    jsonResponse(ctx.response(), result);
                }

                @Override
                public void onFail(String errorMessage, int statusCode) {
                    ctx.response().setStatusCode(statusCode);
                    ctx.response().setStatusMessage(errorMessage);
                    ctx.response().end();
                }
            });
        });
    }

    private String extractUser(HttpServerRequest request) {
        String user = request.headers().get("x-rp-usr");
        if (user == null) {
            user = "Unknown";
        }
        return user;
    }

    private void checkReply(Message<JsonObject> reply, HttpServerRequest request, StatusCode statusCode) {
        if (OK.equals(reply.body().getString(STATUS))) {
            request.response().end();
        } else {
            request.response().setStatusCode(statusCode.getStatusCode());
            request.response().setStatusMessage(statusCode.getStatusMessage());
            request.response().end(statusCode.getStatusMessage());
        }
    }

    private String lastPart(String source, String separator) {
        String[] tokens = source.split(separator);
        return tokens[tokens.length - 1];
    }

    private String part(String source, String separator, int pos) {
        String[] tokens = source.split(separator);
        return tokens[tokens.length - pos];
    }

    private boolean showEmptyQueues(MultiMap requestParams) {
        String showEmptyQueues = StringUtils.getStringOrEmpty(requestParams.get(SHOW_EMPTY_QUEUES_PARAM));
        return showEmptyQueues.equalsIgnoreCase("true") || showEmptyQueues.equals("1");
    }

    private int getMaxQueueItemCountIndex(HttpServerRequest request) {
        int defaultMaxIndex = DEFAULT_MAX_QUEUEITEM_COUNT;
        if (request != null && request.params().contains("limit")) {
            String limitParam = request.params().get("limit");
            try {
                int maxIndex = Integer.parseInt(limitParam) - 1;
                if (maxIndex >= 0) {
                    defaultMaxIndex = maxIndex;
                }
            } catch (NumberFormatException ex) {
                log.warn("Invalid limit parameter '" + limitParam + "' configured for max queue item count. Using default " + DEFAULT_MAX_QUEUEITEM_COUNT);
            }
        }
        return defaultMaxIndex;
    }

    private int extractNumOfQueuesValue(String source, String separator) {
        String numberOfQueuesStr = lastPart(source, separator);
        Integer numQueues;
        try {
            numQueues = Integer.parseInt(numberOfQueuesStr);
        } catch (Exception e) {
            numQueues = DEFAULT_QUEUE_NUM;
            log.warn("Queue size monitoring url was used with wrong or without number of queues param. Using default " + DEFAULT_QUEUE_NUM);
        }

        return numQueues;
    }

    public void handle(HttpServerRequest request) {
        router.handle(request);
    }

    /**
     * Encode the payload from a payloadString or payloadObjet.
     *
     * @param decoded decoded
     * @return String
     */
    public String encode(String decoded) {
        JsonObject object = new JsonObject(decoded);

        String payloadString;
        JsonObject payloadObject = object.getJsonObject("payloadObject");
        if (payloadObject != null) {
            payloadString = payloadObject.encode();
        } else {
            payloadString = object.getString("payloadString");
        }

        if (payloadString != null) {
            object.put(PAYLOAD, payloadString.getBytes(Charset.forName(UTF_8)));
            object.remove("payloadString");
            object.remove("payloadObject");
        }

        // update the content-length
        int length = 0;
        if (object.containsKey(PAYLOAD)) {
            length = object.getBinary(PAYLOAD).length;
        }
        JsonArray newHeaders = new JsonArray();
        for (Object headerObj : object.getJsonArray("headers")) {
            JsonArray header = (JsonArray) headerObj;
            String key = header.getString(0);
            if (key.equalsIgnoreCase("content-length")) {
                JsonArray contentLengthHeader = new JsonArray();
                contentLengthHeader.add("Content-Length");
                contentLengthHeader.add(Integer.toString(length));
                newHeaders.add(contentLengthHeader);
            } else {
                newHeaders.add(header);
            }
        }
        object.put("headers", newHeaders);

        return object.toString();
    }

    /**
     * Decode the payload if the content-type is text or json.
     *
     * @param encoded encoded
     * @return String
     */
    public String decode(String encoded) {
        JsonObject object = new JsonObject(encoded);
        JsonArray headers = object.getJsonArray("headers");
        for (Object headerObj : headers) {
            JsonArray header = (JsonArray) headerObj;
            String key = header.getString(0);
            String value = header.getString(1);
            if (key.equalsIgnoreCase(CONTENT_TYPE) && (value.contains("text/") || value.contains(APPLICATION_JSON))) {
                try {
                    object.put("payloadObject", new JsonObject(new String(object.getBinary(PAYLOAD), Charset.forName(UTF_8))));
                } catch (DecodeException e) {
                    object.put("payloadString", new String(object.getBinary(PAYLOAD), Charset.forName(UTF_8)));
                }
                object.remove(PAYLOAD);
                break;
            }
        }
        return object.toString();
    }

    private void checkLocked(String queue, final HttpServerRequest request, final Handler<Void> handler) {
        request.pause();
        eb.request(redisquesAddress, buildGetLockOperation(queue), (Handler<AsyncResult<Message<JsonObject>>>) reply -> {
            if (NO_SUCH_LOCK.equals(reply.result().body().getString(STATUS))) {
                request.resume();
                request.response().setStatusCode(StatusCode.CONFLICT.getStatusCode());
                request.response().setStatusMessage("Queue must be locked to perform this operation");
                request.response().end("Queue must be locked to perform this operation");
            } else {
                handler.handle(null);
                request.resume();
            }
        });
    }

    private void jsonResponse(HttpServerResponse response, JsonObject object) {
        response.putHeader(CONTENT_TYPE, APPLICATION_JSON);
        response.end(object.encode());
    }
}
