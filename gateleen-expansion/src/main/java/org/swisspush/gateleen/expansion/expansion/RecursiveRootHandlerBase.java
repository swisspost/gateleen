package org.swisspush.gateleen.expansion.expansion;

import org.swisspush.gateleen.core.util.ResourceCollectionException;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The base class of the root handler for
 * the recursive GET feature.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public abstract class RecursiveRootHandlerBase implements DeltaHandler<ResourceNode> {
    protected static final Logger log = LoggerFactory.getLogger(RecursiveRootHandlerBase.class);
    protected AtomicInteger xDeltaResponseNumber = new AtomicInteger(0);

    /**
     * Handles a response error.
     * 
     * @param req
     * @param exception
     */
    void handleResponseError(final HttpServerRequest req, final ResourceCollectionException exception) {
        if (log.isTraceEnabled()) {
            log.trace("got a ResourceCollectionException: " + exception.getMessage());
        }

        ResponseStatusCodeLogUtil.debug(req, exception.getStatusCode(), RecursiveRootHandlerBase.class);
        req.response().setStatusCode(exception.getStatusCode().getStatusCode());
        req.response().setStatusMessage(exception.getStatusCode().getStatusMessage());
        req.response().end(exception.getMessage());
    }

    /**
     * Checks if the given node represents an exception. <br />
     * If it represents an error, an exception is thrown.
     * 
     * @param node
     * @throws ResourceCollectionException
     */
    @SuppressWarnings("unchecked")
    void checkIfError(ResourceNode node) throws ResourceCollectionException {
        // we have an object
        if (node.getObject() != null) {

            // serious error
            if (node.getObject() instanceof ResourceCollectionException) {
                throw (ResourceCollectionException) node.getObject();
            }
            // resource relatec error
            else if (node.getObject() instanceof Map<?, ?>) {
                Map<String, ResourceCollectionException> errorMap = (Map<String, ResourceCollectionException>) node.getObject();
                StringBuilder errorMessage = new StringBuilder("Errors found in resources:\n");

                for (String resource : errorMap.keySet()) {
                    errorMessage.append(resource).append(": ").append(errorMap.get(resource).getMessage()).append("\n");
                }

                if (!errorMap.isEmpty()) {
                    throw new ResourceCollectionException(errorMessage.toString(), StatusCode.INTERNAL_SERVER_ERROR);
                }
            }
        }
    }

    @Override
    public void storeXDeltaResponseHeader(String xDeltaResponseNumber) {
        if (log.isTraceEnabled()) {
            log.trace(" (root) storeXDeltaResponseHeader > " + xDeltaResponseNumber);
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
