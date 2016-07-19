package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.json.JsonObject;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class QueueCircuitBreakerAPI {

    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String STATUS = "status";
    public static final String VALUE = "value";
    public static final String MESSAGE = "message";
    public static final String OPERATION = "operation";
    public static final String PAYLOAD = "payload";
    public static final String CIRCUIT_HASH = "circuit";

    public enum Operation {
        getAllCircuits, getCircuitInformation, getCircuitStatus, closeCircuit, closeAllCircuits;

        Operation(){}

        public static Operation fromString(String op){
            for (Operation operation : values()) {
                if(operation.name().equalsIgnoreCase(op)){
                    return operation;
                }
            }
            return null;
        }
    }

    public static JsonObject buildOperation(Operation operation){
        JsonObject op = new JsonObject();
        op.put(OPERATION, operation.name());
        return op;
    }

    public static JsonObject buildOperation(Operation operation, JsonObject payload){
        JsonObject op = buildOperation(operation);
        op.put(PAYLOAD, payload);
        return op;
    }

    public static JsonObject buildGetCircuitInformationOperation(String circuitHash){
        return buildOperation(Operation.getCircuitInformation, new JsonObject().put(CIRCUIT_HASH, circuitHash));
    }

    public static JsonObject buildGetCircuitStatusOperation(String circuitHash){
        return buildOperation(Operation.getCircuitStatus, new JsonObject().put(CIRCUIT_HASH, circuitHash));
    }

    public static JsonObject buildCloseCircuitOperation(String circuitHash){
        return buildOperation(Operation.closeCircuit, new JsonObject().put(CIRCUIT_HASH, circuitHash));
    }

    public static JsonObject buildCloseAllCircuitsOperation(){
        return buildOperation(Operation.closeAllCircuits);
    }

    public static JsonObject buildGetAllCircuitsOperation(){
        return buildOperation(Operation.getAllCircuits);
    }
}
