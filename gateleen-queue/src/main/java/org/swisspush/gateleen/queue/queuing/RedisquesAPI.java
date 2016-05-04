package org.swisspush.gateleen.queue.queuing;

import io.vertx.core.json.JsonObject;

/**
 * Class RedisquesAPI.
 *
 * @deprecated Use RedisquesAPI class from vertx-redisques module instead
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
@Deprecated
public class RedisquesAPI {

    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String VALUE = "value";
    public static final String STATUS = "status";
    public static final String MESSAGE = "message";
    public static final String PAYLOAD = "payload";
    public static final String OPERATION = "operation";
    public static final String QUEUENAME = "queuename";
    public static final String REQUESTED_BY = "requestedBy";
    public static final String NO_SUCH_LOCK = "No such lock";

    public enum QueueOperation {
        enqueue, check, reset, stop, getListRange, addItem, deleteItem,
        getItem, replaceItem, deleteAllQueueItems, getAllLocks, putLock,
        getLock, deleteLock
    }

    public static JsonObject buildOperation(QueueOperation queueOperation){
        JsonObject op = new JsonObject();
        op.put(OPERATION, queueOperation.name());
        return op;
    }

    public static JsonObject buildOperation(QueueOperation queueOperation, JsonObject payload){
        JsonObject op = buildOperation(queueOperation);
        op.put(PAYLOAD, payload);
        return op;
    }

    public static JsonObject buildCheckOperation(){
        return buildOperation(QueueOperation.check);
    }

    public static JsonObject buildEnqueueOperation(String queueName, String message){
        JsonObject operation = buildOperation(QueueOperation.enqueue, new JsonObject().put(QUEUENAME, queueName));
        operation.put(MESSAGE, message);
        return operation;
    }

    public static JsonObject buildGetListRangeOperation(String queueName, String limit){
        return buildOperation(QueueOperation.getListRange, new JsonObject().put(QUEUENAME, queueName).put("limit", limit));
    }

    public static JsonObject buildAddItemOperation(String queueName, String buffer){
        return buildOperation(QueueOperation.addItem, new JsonObject().put(QUEUENAME, queueName).put("buffer", buffer));
    }

    public static JsonObject buildGetItemOperation(String queueName, int index){
        return buildOperation(QueueOperation.getItem, new JsonObject().put(QUEUENAME, queueName).put("index", index));
    }

    public static JsonObject buildReplaceItemOperation(String queueName, int index, String buffer){
        return buildOperation(QueueOperation.replaceItem, new JsonObject().put(QUEUENAME, queueName).put("index", index).put("buffer", buffer));
    }

    public static JsonObject buildDeleteItemOperation(String queueName, int index){
        return buildOperation(QueueOperation.deleteItem, new JsonObject().put(QUEUENAME, queueName).put("index", index));
    }

    public static JsonObject buildDeleteAllQueueItemsOperation(String queueName){
        return buildOperation(QueueOperation.deleteAllQueueItems, new JsonObject().put(QUEUENAME, queueName));
    }

    public static JsonObject buildGetLockOperation(String queueName){
        return buildOperation(QueueOperation.getLock, new JsonObject().put(QUEUENAME, queueName));
    }

    public static JsonObject buildDeleteLockOperation(String queueName){
        return buildOperation(QueueOperation.deleteLock, new JsonObject().put(QUEUENAME, queueName));
    }

    public static JsonObject buildPutLockOperation(String queueName, String user){
        return buildOperation(QueueOperation.putLock, new JsonObject().put(QUEUENAME, queueName).put(REQUESTED_BY, user));
    }

    public static JsonObject buildGetAllLocksOperation(){
        return buildOperation(QueueOperation.getAllLocks, new JsonObject());
    }
}
