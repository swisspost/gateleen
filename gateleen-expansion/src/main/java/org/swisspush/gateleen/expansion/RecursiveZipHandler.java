package org.swisspush.gateleen.expansion;

import org.swisspush.gateleen.core.util.ResourceCollectionException;
import io.vertx.core.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A handler that allows to put all handeld Json Resources to a zip stream.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class RecursiveZipHandler implements DeltaHandler<ResourceNode> {
    private static final int PROCESS_DONE = 0;
    private static final String HANDLER_PATH = "<HANDLER>";

    private Logger log = LoggerFactory.getLogger(RecursiveExpansionHandler.class);

    private ResourceNode seriousError;
    private String collectionName;
    private AtomicInteger processCount;
    private DeltaHandler<ResourceNode> parentHandler;
    private List<ResourceNode> nodes;
    private AtomicLong xDeltaResponseNumber;

    /**
     * Creates an new instance of the RecursiveZipHandler.
     * 
     * @param subResourceNames subResourceNames
     * @param collectionName collectionName
     * @param parentHandler parentHandler
     */
    public RecursiveZipHandler(List<String> subResourceNames, String collectionName, DeltaHandler<ResourceNode> parentHandler) {
        this.parentHandler = parentHandler;
        this.collectionName = collectionName;
        processCount = new AtomicInteger(subResourceNames.size());
        nodes = new ArrayList<>();
        xDeltaResponseNumber = new AtomicLong(0);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void handle(ResourceNode node) {
        processCount.decrementAndGet();

        // an object (no 404)
        if (node != null) {

            // serious error (eg. max. request limit exceeded)
            if (node.getObject() instanceof ResourceCollectionException && node.getNodeName().equals(ExpansionHandler.SERIOUS_EXCEPTION)) {
                if (log.isTraceEnabled()) {
                    log.trace("(serious error) handle collection '{}'.", collectionName);
                }

                // only the first serious error will be 'passed' down the handler
                if (seriousError == null) {
                    seriousError = node;
                }
            }
            // valid node (either contains a json resource or a list of nodes)
            else {
                // node with list
                if (node.getPath().equals(HANDLER_PATH)) {
                    if (log.isTraceEnabled()) {
                        log.trace("adding collection of '{}' to parent '{}'.", node.getNodeName(), collectionName);
                    }

                    nodes.addAll((List<ResourceNode>) node.getObject());
                }
                // node with simple resource
                else if (!node.getPath().isEmpty()) {
                    if (log.isTraceEnabled()) {
                        log.trace("adding resource '{}' to collection '{}'.", node.getNodeName(), collectionName);
                    }

                    /*
                     * In order to have the objects zipped,
                     * we need a byte array. Because it's faster
                     * to create the byte arrays asynchronously,
                     * we do it in this handler and not in the
                     * corresponding root handler.
                     */
                    node.setObject(((Buffer) node.getObject()).getBytes());
                    nodes.add(node);
                }
            }
        }

        /*
         * if the result is a simple resource,
         * put it to the zip stream.
         */

        // process done
        if (processCount.get() == PROCESS_DONE) {

            // serious error (eg. max subreqest limit)
            if (seriousError != null) {
                parentHandler.handle(seriousError);
            }
            // everything is fine
            else {
                parentHandler.storeXDeltaResponseHeader("" + xDeltaResponseNumber.get());
                parentHandler.handle(new ResourceNode(collectionName, nodes, "", HANDLER_PATH));
            }
        }
    }

    @Override
    public void storeXDeltaResponseHeader(String xDeltaResponseNumber) {
        if (log.isTraceEnabled()) {
            log.trace("storeXDeltaResponseHeader > {}", xDeltaResponseNumber);
        }

        // do we have a x-delta number?
        if (xDeltaResponseNumber != null) {

            try {
                // try to parse it
                long tempxDeltaResponseNumber = Long.parseLong(xDeltaResponseNumber);

                // compare numbers
                if (this.xDeltaResponseNumber.get() < tempxDeltaResponseNumber) {
                    this.xDeltaResponseNumber.set(tempxDeltaResponseNumber);
                }
            } catch (NumberFormatException e) {
                log.warn("Delta response value was not a number", e);
            }
        }
    }
}
