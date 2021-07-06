package org.swisspush.gateleen.routing;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.response.Response;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;


public class RuleUpdateTest {

    private static final Logger logger = LoggerFactory.getLogger(RuleUpdateTest.class);
    private static final String host = "localhost";
    private static final int port = 7012;
    private static final String baseURI = "http://" + host;
    private static final String routingRulesPath = "/playground/server/admin/v1/routing/rules";
    private final int gateleenGracePeriod;
    private static String origRules;

    // Mocked upstream server.
    private static final String upstreamHost = "localhost";
    private static final int upstreamPort = 7011;
    private static final String upstreamPath = "/playground/server/" + RuleUpdateTest.class.getSimpleName() + "/the-other-host";
    private static HttpServer httpServer;

    // Large (mock) resource to play with.
    private static final int largeResourceSeed = 42 * 42;
    private static final String largeResourcePath = upstreamPath + "/my-large-resource.bin";
    private static final int largeResourceSize = 16 * 1024 * 1024; // <- Must be larger than all network buffers together.

    public RuleUpdateTest() {
        try {
            Field gracePeriodField = Router.class.getDeclaredField("GRACE_PERIOD");
            gracePeriodField.setAccessible(true);
            gateleenGracePeriod = (int) gracePeriodField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeClass
    public static void config() throws IOException {
        RestAssured.baseURI = baseURI;
        RestAssured.port = port;
        RestAssured.basePath = "/";
        logger.info("Testing against: " + RestAssured.baseURI + ":" + RestAssured.port);

        // Setup a custom upstream server we can use to download our resource (through gateleen).
        httpServer = Vertx.vertx().createHttpServer().requestHandler(req -> {
            HttpServerResponse rsp = req.response();
            req.exceptionHandler(event -> {
                logger.info("exceptionHandler()");
                rsp.close();
            });
            rsp.setChunked(true);
            InputStream largeResource = newLargeResource(largeResourceSeed, largeResourceSize);
            byte[] buf = new byte[1024*1024];
            Buffer vBuf = Buffer.buffer(buf);
            rsp.drainHandler(event -> {
                int readLen;
                logger.debug("pump()");
                try {
                    readLen = largeResource.read(buf);
                } catch (IOException e) {
                    throw new RuntimeException("Not impl", e);
                }
                if (readLen == -1){
                    logger.info("response.end()");
                    rsp.end();
                    return;
                }
                vBuf.setBytes(0, buf, 0, readLen);
                rsp.write(vBuf);
            });
        });
        httpServer.listen(upstreamPort, upstreamHost);
        // Then register that server as a static route.
        putCustomUpstreamRoute();
    }

    @AfterClass
    public static void after() throws IOException {
        httpServer.close();
        if (origRules != null) {
            // Restore routing rules.
            customPut(routingRulesPath, "application/json", new ByteArrayInputStream(origRules.getBytes(UTF_8)));
            origRules = null;
        }
    }

    @Test
    public void gateleenMustProperlyCloseItsDownstreamResponse() throws InterruptedException, IOException {

        // Initiate a GET request to our large-resource. But give gateleen some time to update rules beforehand.
        Thread.sleep(42);
        final InputStream body = newLazyResponseStream(largeResourcePath, gateleenGracePeriod + 12_000);

        // The response stream now has started (but we don't consume anything yet). So
        // now we trigger our rules update (which will corrupt our stream).
        logger.info("Trigger routing rules udpate");
        triggerRoutingRulesUpdate();

        // There's a timeout configured in gateleen. If we consume the stream too fast,
        // our problem does not occur. So we have to wait until gateleen actually
        // performs his "rule update". Or to be more accurate blindly closes the old connections.
        logger.info("Wait for gateleen to close his old clients (takes a while ...).");
        Thread.sleep(gateleenGracePeriod + 1000); // Just a tiny bit more.

        // Now gateleen should already have closed our response. We now can read all that stuff
        // which already was on-the-wire before gateleen sent the RST.
        logger.info("Start reading the response.");
        // This 'read()' call here will run into a timeout because gateleen does not
        // deliver any further data on that stream (no matter how long we wait).
        {
            long bytesSoFar = 0;
            byte[] buf = new byte[512 * 1024];
            while (true) {
                int len = body.read(buf);
                if (len == -1) {
                    break; // EOF
                }
                bytesSoFar += len;
                logger.trace(String.format("Read %9d of %9d bytes (%3d%%)", bytesSoFar, largeResourceSize, bytesSoFar*100 / largeResourceSize));
            }
            logger.info("EOF reached after {} bytes of expected {} bytes.", bytesSoFar, largeResourceSize);
            Assert.assertEquals(largeResourceSize, bytesSoFar);
        }
    }

    @Test
    public void errorInStreamMustBeRecognizableOnClient() throws IOException, InterruptedException {

        // Initiate a GET request to our large-resource.
        final InputStream body = newLazyResponseStream(largeResourcePath, gateleenGracePeriod + 12_000);

        // The response stream now has started (but we don't consume anything yet). So
        // now we trigger our rules update (which will corrupt our stream).
        logger.info("Trigger routing rules udpate");
        triggerRoutingRulesUpdate();

        // There's a timeout configured in gateleen. If we consume the stream too fast,
        // our problem does not occur. So we have to wait until gateleen actually
        // performs his "rule update". Or to be more accurate blindly closes the old connections.
        logger.info("Wait for gateleen to close his old clients (takes a while ...).");
        Thread.sleep(gateleenGracePeriod + 1000); // Just a tiny bit more.

        // Now gateleen should already have closed our response. We now can read all that stuff
        // which already was on-the-wire before gateleen sent the RST.
        logger.info("Start reading the response.");
        // This 'read()' call here will run into a timeout because gateleen does not
        // deliver any further data on that stream (no matter how long we wait).
        int bytesSoFar = 0;
        {
            // Open the same stream as we expect to download. So we can validate what we download.
            InputStream referenceSrc = newLargeResource(largeResourceSeed, largeResourceSize);
            byte[] dload = new byte[128 * 1024];
            while (true) {
                int readLen = body.read(dload);
                if (readLen == -1) {
                    break; // EOF
                }
                bytesSoFar += readLen;
                // Validate that downloaded stream content is correct.
                for (int i = 0; i < readLen; ++i) {
                    int cExp = referenceSrc.read();
                    int cAct = (dload[i] & 0xFF);
                    if (cExp != cAct) { // Only to log some debugging info.
                        int offset = i > 5 ? bytesSoFar + i - 5 : bytesSoFar;
                        int snipLength = Math.min(42, readLen - i); // Print maximally
                       logger.debug("Stream at offset={} length={} is: '{}'",
                                offset, snipLength, new String(dload, offset - bytesSoFar, snipLength, ISO_8859_1));
                    }
                    Assert.assertEquals("Stream must not contain incorrect data", cExp, cAct);
                }
                logger.trace(String.format("Read %9d of %9d bytes (%3d%%)", bytesSoFar, largeResourceSize, bytesSoFar*100 / largeResourceSize));
            }
            logger.info("EOF reached on response.");
        }
        Assert.assertEquals(largeResourceSize, bytesSoFar);
    }

    /**
     * Produces some binary garbage based on the passed seed. Useful to produce
     * large payloads in a reproducible way.
     */
    private static InputStream newLargeResource(int seed, int size) {
        return new InputStream() {
            int producedBytes = 0;
            final Random random = new Random(seed);

            public int read() throws IOException {
                if (producedBytes >= size) {
                    return -1; // EOF
                }
                producedBytes += 1;
                return random.nextInt() & 0x000000FF; // Just another (pseudo) random byte.
            }
        };
    }

    private InputStream newLazyResponseStream(String path, int readTimeoutMs) throws IOException {

        Assert.assertTrue(path.startsWith("/"));

        // Request
        final URL url = new URL(baseURI + ":" + port + path);
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoInput( true );
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty( "Method" , "GET" );
        connection.setRequestProperty( "Accept" , "application/octet-stream" ); // NO! I don't wanna your index.html ...

        // Response
        Assert.assertEquals(200, connection.getResponseCode());
        return connection.getInputStream();
    }

    /**
     * I liked to use RestAssured. But cannot because:
     * - We MUST use Content-Type "application/javascript" (which is a non-existing
     *   type BTW) as we cannot use "application/octet-stream" due to the gateleen
     *   constraint handler.
     * - RestAssured is unable to "encode" binary garbage as "application/javascript".
     *
     * So just wrote the HTTP PUT in pure java. And guess what: Just works.
     */
    private static void customPut(String path, String contentType, InputStream body) throws IOException {
        Assert.assertTrue(path.startsWith("/"));
        HttpURLConnection connection = (HttpURLConnection) new URL(baseURI + ":" + port + path).openConnection();
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setChunkedStreamingMode(8192);
        connection.setRequestMethod("PUT");
        connection.addRequestProperty("Content-Type", contentType);
        byte[] buf = new byte[1024];
        OutputStream snk = connection.getOutputStream();
        while (true) {
            int readLen = body.read(buf);
            if (readLen == -1) break; // EOF
            snk.write(buf, 0, readLen);
        }
        snk.close();
        Assert.assertEquals(200, connection.getResponseCode());
        // Consume body (which should be empty)
        InputStream rspBody = connection.getInputStream();
        while (rspBody.read() != -1) ;
    }

    private void triggerRoutingRulesUpdate() {
        Response rsp = RestAssured.get(routingRulesPath);
        Assert.assertEquals(200, rsp.statusCode());
        RestAssured.given().header("Content-Type", "application/json").body(rsp.body().print()).put(routingRulesPath);
    }

    private static void putCustomUpstreamRoute() throws IOException {
        if (origRules != null) {
            logger.warn("custom rule already installed.");
            return;
        }
        // Get rules
        Response rsp = RestAssured.get(routingRulesPath);
        Assert.assertEquals(200, rsp.statusCode());

        // Prepend our custom rule for our upstream server. Kind like a hack but
        // playground has no route configured to a external host.
        origRules = rsp.body().print();
        String customRules = origRules.replaceFirst("^\\{", "{\"" + upstreamPath + "/(.*)\": {" +
                "    \"url\": \"http://" + upstreamHost + ":" + upstreamPort + "/\\$1\"" +
                "},");

        // Push it back to re-configure gateleen server.
        customPut(routingRulesPath, "application/json", new ByteArrayInputStream(customRules.getBytes(UTF_8)));
    }

}
