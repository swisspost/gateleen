package org.swisspush.gateleen.expansion;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.reststorage.MimeTypeResolver;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Enables you to directly browse into a zip file and get the underlying resource. <br>
 * <code>
 *     GET /gateleen/zips/111111.zip/this/is/my/resource
 * </code>
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class ZipExtractHandler {
    private static final String ZIP_RESOURCE_FLAG = ".zip/";
    private static final int DEFAULT_TIMEOUT = 120000;
    private static final byte[] ZIP_BUFFER_SIZE = new byte[2048];
    private static final String DEFAULT_MIME_TYPE = "application/json";

    private final HttpClient selfClient;
    private final MimeTypeResolver mimeTypeResolver;


    /**
     * Creates a new instance of the ZipExtractHandler.
     *
     * @param selfClient
     */
    public ZipExtractHandler(HttpClient selfClient) {
        this.selfClient = selfClient;
        this.mimeTypeResolver = new MimeTypeResolver(DEFAULT_MIME_TYPE);
    }

    /**
     * If we have a zip resource with a given path,
     * we will handle the request, otherwise not.
     *
     * @param req
     * @return true if request is handled, otherwise false
     */
    public boolean handle(final HttpServerRequest req) {
        if ( req.method().equals(HttpMethod.GET) && req.uri().contains(ZIP_RESOURCE_FLAG) ) {
            // zip resource and path
            int seperationIndex = req.uri().lastIndexOf(ZIP_RESOURCE_FLAG) + ZIP_RESOURCE_FLAG.length() - 1;
            String zipUrl = req.uri().substring(0, seperationIndex);
            String insidePath = req.uri().substring(seperationIndex + 1);

            // perform the get of the zip
            performGETRequest(req, zipUrl, insidePath);

            return true;
        }
        else {
            return false;
        }
    }

    /**
     * Performs the initial GET request of the zip resource.
     *
     * @param req
     * @param zipUrl
     * @param insidePath
     */
    protected void performGETRequest(final HttpServerRequest req, final String zipUrl, final String insidePath) {
        Logger log = RequestLoggerFactory.getLogger(ZipExtractHandler.class, req);

        // perform Initial GET request
        HttpClientRequest selfRequest = selfClient.get(zipUrl, response -> {
            if (response.statusCode() == StatusCode.OK.getStatusCode()) {
                extractResourceFromZip(req, zipUrl, insidePath, response);
            } else {
                log.debug("GET of zip resource {} failed.", zipUrl);
                createResponse(req, response.statusCode(), response.statusMessage(), null, null);
            }
        });

        // setting headers
        selfRequest.headers().setAll(req.headers());

        // avoids blocking other requests
        selfRequest.setTimeout(DEFAULT_TIMEOUT);

        // fire
        selfRequest.end();
    }

    /**
     * Creates the response to the original request.
     *
     * @param req
     * @param statusCode
     * @param statusMessage
     * @param buffer
     * @param mimeType
     */
    private void createResponse(final HttpServerRequest req, final int statusCode, final String statusMessage, final Buffer buffer, final String mimeType) {
        ResponseStatusCodeLogUtil.info(req, StatusCode.fromCode(statusCode), ZipExtractHandler.class);
        req.response().setStatusCode(statusCode);
        req.response().setStatusMessage(statusMessage);

        if ( mimeType != null ) {
            req.response().headers().add("Content-Type", mimeType);
        }

        if ( buffer != null ) {
            req.response().end(buffer);
        }
        else {
            req.response().end();
        }
    }

    /**
     * Extract the wished resource from the zip file.
     *
     * @param req
     * @param zipUrl
     * @param insidePath
     * @param response
     */
    private void extractResourceFromZip(final HttpServerRequest req, final String zipUrl, final String insidePath, final HttpClientResponse response) {
        Logger log = RequestLoggerFactory.getLogger(ZipExtractHandler.class, req);

        response.bodyHandler(buffer -> {
            // read the zip from the buffer

            try (ByteArrayInputStream bInputStream = new ByteArrayInputStream(buffer.getBytes());
                 ZipInputStream inputStream = new ZipInputStream(bInputStream)) {

                ZipEntry entry;
                Buffer contentBuffer = Buffer.buffer();
                boolean foundEntry = false;

                while ((entry = inputStream.getNextEntry()) != null) {
                    // if name does not match, go on ...
                    if ( ! entry.getName().equalsIgnoreCase(insidePath) ) {
                        continue;
                    }

                    // extract the specific resource
                    int len = 0;
                    while ((len = inputStream.read(ZIP_BUFFER_SIZE)) > 0) {
                        contentBuffer.appendBytes(ZIP_BUFFER_SIZE, 0, len);
                    }

                    foundEntry = true;

                    // only one resource my be extracted this way
                    break;
                }

                if ( foundEntry ) {
                    // append content to response
                    createResponse(req, StatusCode.OK.getStatusCode(),StatusCode.OK.getStatusMessage(), contentBuffer, mimeTypeResolver.resolveMimeType(insidePath));
                }
                else {
                    // return 404 - not found
                    log.error("could not extract {} from {}", insidePath, zipUrl);
                    createResponse(req, StatusCode.NOT_FOUND.getStatusCode(),StatusCode.NOT_FOUND.getStatusMessage(), null, null);
                }
            } catch (Exception e) {
                log.error("could not extract {} from {}: {}", insidePath, zipUrl, e.getMessage());
                createResponse(req, StatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),StatusCode.INTERNAL_SERVER_ERROR.getStatusMessage(), null, null);
            }
        });
    }
}
