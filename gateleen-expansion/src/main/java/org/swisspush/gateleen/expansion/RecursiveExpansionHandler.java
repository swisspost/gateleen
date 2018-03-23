package org.swisspush.gateleen.expansion;

import org.swisspush.gateleen.core.util.ResourceCollectionException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A class for handeling the recursive get request.
 * She holds exactly one collection and handles all of their
 * children, before she calls here parent.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class RecursiveExpansionHandler implements DeltaHandler<ResourceNode> {
    private static final int PROCESS_DONE = 0;
    private Logger log = LoggerFactory.getLogger(RecursiveExpansionHandler.class);

    private ResourceNode seriousError;
    private AtomicInteger processCount;
    private String collectionName;
    private DeltaHandler<ResourceNode> parentHandler;
    private Map<String, ResourceNode> nodeMap;
    private String collectioneTag;
    private Map<String, ResourceCollectionException> resourceCollectionExceptionMap;
    private AtomicInteger xDeltaResponseNumber;

    /**
     * Creates a new instance of the RecursiveExpansionHandler.
     * 
     * @param subResourceNames - a list with the child names, needed for preserving the order of the resources
     * @param collectionName - the name of the collection
     * @param collectioneTag - eTag of the request that lead to this collection
     * @param parentHandler - the parent handler
     */
    public RecursiveExpansionHandler(List<String> subResourceNames, String collectionName, String collectioneTag, DeltaHandler<ResourceNode> parentHandler) {
        if (log.isTraceEnabled()) {
            log.trace("RecursiveExpansionHandler created for collection '" + collectionName + "' with a child count of " + subResourceNames.size() + ".");
        }

        this.xDeltaResponseNumber = new AtomicInteger(0);
        this.processCount = new AtomicInteger(subResourceNames.size());
        this.parentHandler = parentHandler;
        this.collectionName = collectionName;
        this.collectioneTag = collectioneTag;

        resourceCollectionExceptionMap = new HashMap<>();
        nodeMap = createEmptyNodeMap(subResourceNames);
    }

    /**
     * Creates a linked HashMap with the capacity of the
     * subResourceNames. The subResourceNames are used
     * as keys and the value is set per default as null.
     * 
     * @param subResourceNames subResourceNames
     * @return Map
     */
    private Map<String, ResourceNode> createEmptyNodeMap(List<String> subResourceNames) {
        Map<String, ResourceNode> map = new LinkedHashMap<>(subResourceNames.size());
        for (String resourceName : subResourceNames) {
            map.put(resourceName.replace("/", ""), null);
        }
        return map;
    }

    /**
     * Handles the given node.
     * A node has to field, his name
     * and an object. Normaly this object is either
     * a JsonObject or an JSonArray.
     * If an error occures, this object can
     * carry an exception (serious error) or
     * null (no serious error).
     * 
     * @param node node
     */
    @SuppressWarnings("unchecked")
    @Override
    public void handle(ResourceNode node) {
        processCount.decrementAndGet();

        /*
         * a node can contain:
         * > null
         * > JsonObject
         * > - JsonArray
         * > Map
         * > - ResourceCollectionException
         */

        // we have a node (no 404)
        if (node != null) {

            /*
             * if the data is NOT json, an
             * error will be created.
             */

            // pure data
            if (node.getObject() instanceof Buffer) {
                try {
                    node.setObject(new JsonObject(((Buffer) node.getObject()).toString("UTF-8")));
                } catch (Exception e) {
                    log.error("Error in result of sub resource with path '" + node.getPath() + "' Message: " + e.getMessage());
                    node.setObject(new ResourceCollectionException(e.getMessage()));
                }
            }

            // Json Object or Array
            if (node.getObject() instanceof JsonObject || node.getObject() instanceof JsonArray) {
                if (log.isTraceEnabled()) {
                    log.trace("handle collection '" + collectionName + "' for node '" + node.getNodeName() + "'.");
                }

                nodeMap.put(node.getNodeName(), node);
            }
            // error
            else if (node.getObject() instanceof ResourceCollectionException) {
                // serious error (eg. max. request limit exceeded)
                if (node.getNodeName().equals(ExpansionHandler.SERIOUS_EXCEPTION)) {
                    if (log.isTraceEnabled()) {
                        log.trace("(serious error) handle collection '" + collectionName + "'.");
                    }

                    // only the first serious error will be 'passed' down the handler
                    if (seriousError == null) {
                        seriousError = node;
                    }
                }
                // resource related error occured
                else {
                    if (log.isTraceEnabled()) {
                        log.trace("(no serious error) handle collection '" + collectionName + "'.");
                    }

                    resourceCollectionExceptionMap.put(node.getNodeName(), (ResourceCollectionException) node.getObject());
                }
            }
            // error collection
            else if (node.getObject() instanceof Map<?, ?>) {
                resourceCollectionExceptionMap.putAll((Map<String, ResourceCollectionException>) node.getObject());
            } else {
                if (log.isTraceEnabled()) {
                    log.trace("No match found for handling node. This should not happen!");
                }
            }
        }

        // process done
        if (processCount.get() == PROCESS_DONE) {
            if (log.isTraceEnabled()) {
                log.trace("finishing process");
                log.trace(" -> serious error:    " + (seriousError != null));
                log.trace(" -> resource errors:  " + (!resourceCollectionExceptionMap.isEmpty()));
            }

            // serious error occured
            if (seriousError != null) {
                parentHandler.handle(seriousError);
            }
            // resource related errors occured
            else if (!resourceCollectionExceptionMap.isEmpty()) {
                parentHandler.handle(new ResourceNode(collectionName, resourceCollectionExceptionMap));
            }
            // everything is fine
            else {
                /*
                 * creating a json object
                 * based on the linked map
                 * for preserving the order
                 * of the resources.
                 */

                StringBuilder eTags = new StringBuilder();
                eTags.append(collectioneTag);

                JsonObject nodes = new JsonObject();

                for (String key : nodeMap.keySet()) {
                    ResourceNode orderedNode = nodeMap.get(key);
                    eTags.append(orderedNode.geteTag());
                    nodes.put(key, orderedNode.getObject());
                }

                parentHandler.storeXDeltaResponseHeader("" + xDeltaResponseNumber.get());
                parentHandler.handle(new ResourceNode(collectionName, nodes, eTags.toString()));
            }
        }
    }

    @Override
    public void storeXDeltaResponseHeader(String xDeltaResponseNumber) {
        if (log.isTraceEnabled()) {
            log.trace("storeXDeltaResponseHeader > " + xDeltaResponseNumber);
        }

        // do we have a x-delta number?
        if (xDeltaResponseNumber != null) {

            try {
                // try to parse it
                int tempxDeltaResponseNumber = Integer.parseInt(xDeltaResponseNumber);

                // compare numbers
                if (this.xDeltaResponseNumber.get() < tempxDeltaResponseNumber) {
                    this.xDeltaResponseNumber.set(tempxDeltaResponseNumber);
                }
            } catch (NumberFormatException e) {
                // ignored
            }
        }
    }
}
