package org.swisspush.gateleen.expansion;

import org.swisspush.gateleen.core.util.ExpansionDeltaUtil;
import org.swisspush.gateleen.core.util.ResourceCollectionException;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Creates a root handler for the recursive ZIP GET.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class RecursiveZipRootHandler extends RecursiveRootHandlerBase {
    private static final int DATA_BLOCK_SIZE = 2048;
    private static final String CONTENT_TYPE_HEADER = "Content-type";
    private static final String CONTENT_TYPE_ZIP = "application/octet-stream";

    private final HttpServerRequest req;
    private final String serverRoot;

    private final Buffer data;
    private final Set<String> finalOriginalParams;

    /**
     * Creates an instance of the root handler for the
     * recursive HTTP GET.
     * 
     * @param req req
     * @param serverRoot serverRoot
     * @param data data
     * @param finalOriginalParams finalOriginalParams
     */
    public RecursiveZipRootHandler(final HttpServerRequest req, String serverRoot, Buffer data, Set<String> finalOriginalParams) {
        this.req = req;
        this.serverRoot = serverRoot;
        this.data = data;
        this.finalOriginalParams = finalOriginalParams;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void handle(ResourceNode node) {
        if (log.isTraceEnabled()) {
            log.trace("parent handler called");
        }

        try {
            ExpansionDeltaUtil.verifyCollectionResponse(req, data, finalOriginalParams);

            // throw the given error (if any)
            checkIfError(node);

            /*
             * Zip Streams are not thread safe.
             * You can't access / write them asynchronously.
             * For this reason you have to collect the needed
             * data and put it together at the end.
             */

            // zip the collection
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);) {

                List<ResourceNode> zipableNodes = (List<ResourceNode>) node.getObject();

                for (ResourceNode resourceNode : zipableNodes) {
                    if (log.isTraceEnabled()) {
                        log.trace("Create zip for: " + resourceNode.getNodeName());
                        log.trace("   >> " + resourceNode.getPath());
                    }

                    zipEntry(zipOutputStream, resourceNode);
                }

                zipOutputStream.finish();

                req.response().headers().set(CONTENT_TYPE_HEADER, CONTENT_TYPE_ZIP);

                if (finalOriginalParams.contains("delta")) {
                    req.response().headers().set("x-delta", "" + xDeltaResponseNumber);
                }

                ResponseStatusCodeLogUtil.debug(req, StatusCode.OK, RecursiveExpansionRootHandler.class);
                req.response().end(Buffer.buffer(outputStream.toByteArray()));
            } catch (Exception e) {
                log.error("Error while writing zip: " + e.getMessage(), e);
                createErrorResponse(e);
            }
        } catch (ResourceCollectionException exception) {
            handleResponseError(req, exception);
        }
    }

    /**
     * Zips the given ResourceNode.
     * 
     * @param zipOutputStream zipOutputStream
     * @param resourceNode resourceNode
     */
    private void zipEntry(ZipOutputStream zipOutputStream, ResourceNode resourceNode) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream((byte[]) resourceNode.getObject())) {

            ZipEntry zipEntry = new ZipEntry(createNewZipEntryName(resourceNode.getPath()));
            zipOutputStream.putNextEntry(zipEntry);

            byte[] b = new byte[DATA_BLOCK_SIZE];
            int byteCount;

            while ((byteCount = inputStream.read(b, 0, DATA_BLOCK_SIZE)) != -1) {
                zipOutputStream.write(b, 0, byteCount);
            }

            zipOutputStream.closeEntry();
            inputStream.close();
        } catch (Exception e) {
            log.error("Error while writing zip entry '" + resourceNode.getNodeName() + "'.", e);
        }
    }

    /**
     * Creates an error response.
     * 
     * @param exception exception
     */
    private void createErrorResponse(Exception exception) {
        ResponseStatusCodeLogUtil.debug(req, StatusCode.INTERNAL_SERVER_ERROR, RecursiveZipRootHandler.class);
        req.response().setStatusCode(StatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
        req.response().setStatusMessage(StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage());
        req.response().end(exception.getMessage());
    }

    /**
     * Creates a suitable name for the zip entry.
     * 
     * @param path path
     * @return String
     */
    private String createNewZipEntryName(String path) {
        return path.replace(serverRoot + "/", "");
    }
}
