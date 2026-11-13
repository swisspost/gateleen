package org.swisspush.gateleen.routing;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.swisspush.gateleen.logging.LogAppenderRepository;
import org.swisspush.gateleen.logging.LoggingHandler;
import org.swisspush.gateleen.logging.LoggingResource;
import org.swisspush.gateleen.logging.LoggingResourceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(VertxUnitRunner.class)
public class NullForwarderTest {

    private Vertx vertx;
    private HttpClient client;
    private AbstractAppender loggingAppender;
    private org.apache.logging.log4j.core.Logger loggingLogger;
    private Level previousLoggingLevel;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(true).setMaxPoolSize(1));
    }

    @After
    public void tearDown(TestContext context) {
        if (loggingAppender != null) {
            loggingLogger.removeAppender(loggingAppender);
            Configurator.setLevel(loggingLogger.getName(), previousLoggingLevel);
            loggingAppender.stop();
        }
        vertx.close(context.asyncAssertSuccess());
    }

    @Test
    public void testConsumedRequestCompletesEmptyResponsesOnOneKeepAliveConnection(TestContext context) {
        Async async = context.async();
        List<ServerConnection> connections = new ArrayList<>();
        List<String> loggingMessages = captureLoggingMessages();
        Router router = Router.router(vertx);
        router.route().handler(ctx -> ctx.request().bodyHandler(body -> ctx.next()));
        router.route().handler(nullForwarder());

        startServer(router, connections).onComplete(serverResult -> {
            if (serverResult.failed()) {
                context.fail(serverResult.cause());
                async.complete();
                return;
            }
            int port = serverResult.result();
            sendPut(port, "first request body")
                    .compose(first -> sendPut(port, "second request body").map(second -> List.of(first, second)))
                    .onComplete(result -> {
                        if (result.failed()) {
                            context.fail(result.cause());
                        } else {
                            List<Response> responses = result.result();
                            for (Response response : responses) {
                                context.assertEquals(200, response.statusCode);
                                context.assertEquals("0", response.contentLength);
                                context.assertEquals(0, response.body.length());
                            }
                            context.assertEquals(2, connections.size());
                            context.assertTrue(connections.get(0).connection == connections.get(1).connection,
                                    "Both requests must use the same server-side connection");
                            context.assertEquals(connections.get(0).remoteAddress, connections.get(1).remoteAddress,
                                    "Both requests must use the same client socket");
                            context.assertEquals(2, loggingMessages.size());
                            context.assertTrue(loggingMessages.stream()
                                    .allMatch(message -> message.equals("request will not be logged")));
                        }
                        async.complete();
                    });
        });
        async.awaitSuccess(5000);
    }

    @Test
    public void testUnconsumedRequestBodyIsDrainedBeforeResponseCompletes(TestContext context) {
        Async async = context.async();
        List<ServerConnection> connections = new ArrayList<>();
        List<String> loggingMessages = captureLoggingMessages();
        Router router = Router.router(vertx);
        router.route().handler(nullForwarder());

        startServer(router, connections).onComplete(serverResult -> {
            if (serverResult.failed()) {
                context.fail(serverResult.cause());
                async.complete();
                return;
            }
            sendPut(serverResult.result(), "streamed request body").onComplete(result -> {
                if (result.failed()) {
                    context.fail(result.cause());
                } else {
                    Response response = result.result();
                    context.assertEquals(200, response.statusCode);
                    context.assertEquals("0", response.contentLength);
                    context.assertEquals(0, response.body.length());
                    context.assertEquals(1, connections.size());
                    context.assertEquals(1, loggingMessages.size());
                    context.assertEquals("request will not be logged", loggingMessages.get(0));
                }
                async.complete();
            });
        });
        async.awaitSuccess(5000);
    }

    private Handler<io.vertx.ext.web.RoutingContext> nullForwarder() {
        LoggingResourceManager loggingResourceManager = mock(LoggingResourceManager.class);
        when(loggingResourceManager.getLoggingResource()).thenReturn(new LoggingResource());
        EventBus eventBus = vertx.eventBus();
        Rule rule = new Rule();
        rule.setUrlPattern("/null/.*");
        return new NullForwarder(rule, loggingResourceManager, mock(LogAppenderRepository.class), null, eventBus)::handle;
    }

    private Future<Integer> startServer(Router router, List<ServerConnection> connections) {
        HttpServer server = vertx.createHttpServer().requestHandler(request -> {
            connections.add(ServerConnection.of(request));
            router.handle(request);
        });
        return server.listen(0).map(HttpServer::actualPort);
    }

    private Future<Response> sendPut(int port, String body) {
        return client.request(HttpMethod.PUT, port, "localhost", "/null/test")
                .compose(request -> request.send(Buffer.buffer(body)))
                .compose(response -> response.body().map(bodyBuffer -> new Response(
                        response.statusCode(), response.getHeader("Content-Length"), bodyBuffer)));
    }

    private List<String> captureLoggingMessages() {
        List<String> messages = new CopyOnWriteArrayList<>();
        loggingAppender = new AbstractAppender("null-forwarder-test", null,
                PatternLayout.createDefaultLayout(), false, null) {
            @Override
            public void append(LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        loggingAppender.start();
        loggingLogger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(LoggingHandler.class);
        previousLoggingLevel = loggingLogger.getLevel();
        loggingLogger.addAppender(loggingAppender);
        Configurator.setLevel(loggingLogger.getName(), Level.INFO);
        return messages;
    }

    private static final class ServerConnection {
        private final HttpConnection connection;
        private final String remoteAddress;

        private ServerConnection(HttpConnection connection, String remoteAddress) {
            this.connection = connection;
            this.remoteAddress = remoteAddress;
        }

        private static ServerConnection of(HttpServerRequest request) {
            return new ServerConnection(request.connection(),
                    request.connection().remoteAddress().toString());
        }
    }

    private static final class Response {
        private final int statusCode;
        private final String contentLength;
        private final Buffer body;

        private Response(int statusCode, String contentLength, Buffer body) {
            this.statusCode = statusCode;
            this.contentLength = contentLength;
            this.body = body;
        }
    }
}
