package org.swisspush.gateleen.queue.queuing.circuitbreaker;

import io.vertx.core.json.JsonObject;

/**
 * Helper class to simplify work with the QueueCircuitBreaker API.
 * Provides methods to build correct operation JsonObjects
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
class QueueCircuitBreakerAPI {

    static final String OK = "ok";
    static final String ERROR = "error";
    static final String STATUS = "status";
    static final String VALUE = "value";
    static final String MESSAGE = "message";
    static final String OPERATION = "operation";
    static final String PAYLOAD = "payload";
    static final String CIRCUIT_HASH = "circuit";

    /**
     * Enumeration of the available 'features' of the API
     */
    enum Operation {
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

    private static JsonObject buildOperation(Operation operation){
        JsonObject op = new JsonObject();
        op.put(OPERATION, operation.name());
        return op;
    }

    private static JsonObject buildOperation(Operation operation, JsonObject payload){
        JsonObject op = buildOperation(operation);
        op.put(PAYLOAD, payload);
        return op;
    }

    static JsonObject buildGetCircuitInformationOperation(String circuitHash){
        return buildOperation(Operation.getCircuitInformation, new JsonObject().put(CIRCUIT_HASH, circuitHash));
    }

    static JsonObject buildGetCircuitStatusOperation(String circuitHash){
        return buildOperation(Operation.getCircuitStatus, new JsonObject().put(CIRCUIT_HASH, circuitHash));
    }

    static JsonObject buildCloseCircuitOperation(String circuitHash){
        return buildOperation(Operation.closeCircuit, new JsonObject().put(CIRCUIT_HASH, circuitHash));
    }

    static JsonObject buildCloseAllCircuitsOperation(){
        return buildOperation(Operation.closeAllCircuits);
    }

    static JsonObject buildGetAllCircuitsOperation(){
        return buildOperation(Operation.getAllCircuits);
    }
}
