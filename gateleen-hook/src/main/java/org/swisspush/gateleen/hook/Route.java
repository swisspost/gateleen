package org.swisspush.gateleen.hook;

import org.swisspush.gateleen.logging.LoggingResourceManager;
import org.swisspush.gateleen.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.routing.Forwarder;
import org.swisspush.gateleen.routing.Rule;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a Route for a specific
 * hook. Each source (eg. listener) has its own Route!
 * The Route forwards a request for a source (eg. listener) to
 * the destination specified by the HttpHook.
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class Route {
    /** How long to let the http clients live before closing them after a update on the forwarder */
    private static final int GRACE_PERIOD = 30000;
    private static final int CLIENT_DEFAULT_TIMEOUT_SEC = 30;
    private static final int CLIENT_DEFAULT_POOL_SIZE = 5000;
    private static final boolean CLIENT_DEFAULT_KEEP_ALIVE = true;
    private static final boolean CLIENT_DEFAULT_EXPAND_ON_BACKEND = false;
    private static final boolean CLIENT_DEFAULT_EXPAND_IN_STORAGE = false;
    private static final int CLIENT_DEFAULT_LOG_EXPIRY = 4 * 3600;

    private static Pattern URL_PARSE_PATTERN = Pattern.compile("^(?<scheme>https?)://(?<host>[^/:]+)(:(?<port>[0-9]+))?(?<path>/.*)$");
    private static Logger LOG = LoggerFactory.getLogger(Route.class);

    private Vertx vertx;
    private LoggingResourceManager loggingResourceManager;
    private MonitoringHandler monitoringHandler;
    private String userProfilePath;
    private ResourceStorage storage;

    private String urlPattern;
    private HttpHook httpHook;

    private Rule rule;
    private HttpClient client;
    private Forwarder forwarder;

    /**
     * Creates a new instance of a Route.
     * 
     * @param vertx vertx
     * @param storage storage
     * @param loggingResourceManager loggingResourceManager
     * @param monitoringHandler monitoringHandler
     * @param userProfilePath userProfilePath
     * @param httpHook httpHook
     * @param urlPattern - this can be a listener or a normal urlPattern (eg. for a route)
     */
    public Route(Vertx vertx, ResourceStorage storage, LoggingResourceManager loggingResourceManager, MonitoringHandler monitoringHandler, String userProfilePath, HttpHook httpHook, String urlPattern) {
        this.vertx = vertx;
        this.storage = storage;
        this.loggingResourceManager = loggingResourceManager;
        this.monitoringHandler = monitoringHandler;
        this.userProfilePath = userProfilePath;
        this.httpHook = httpHook;
        this.urlPattern = urlPattern;

        createRule();

        createHttpClient();

        createForwarder();
    }

    /**
     * Creates the forwarder for this hook.
     */
    private void createForwarder() {
        forwarder = new Forwarder(vertx, client, rule, storage, loggingResourceManager, monitoringHandler, userProfilePath);
    }

    /**
     * Creates a new rule for the forwarder,
     * depending on the given httpHook.
     */
    private void createRule() {
        rule = new Rule();
        rule.setUrlPattern(urlPattern);

        Matcher urlMatcher = URL_PARSE_PATTERN.matcher(httpHook.getDestination());

        if (!urlMatcher.matches()) {
            throw new IllegalArgumentException("Invalid url for " + httpHook.getDestination());
        }

        rule.setHost(urlMatcher.group("host"));
        rule.setScheme(urlMatcher.group("scheme"));
        rule.setPort(rule.getScheme().equals("https") ? 443 : 80);

        String portString = urlMatcher.group("port");
        if (portString != null) {
            rule.setPort(Integer.parseInt(portString));
        }

        rule.setPath(urlMatcher.group("path"));

        rule.setTimeout(1000 * CLIENT_DEFAULT_TIMEOUT_SEC);
        rule.setPoolSize(CLIENT_DEFAULT_POOL_SIZE);
        rule.setKeepAlive(CLIENT_DEFAULT_KEEP_ALIVE);
        rule.setExpandOnBackend(CLIENT_DEFAULT_EXPAND_ON_BACKEND);
        rule.setStorageExpand(CLIENT_DEFAULT_EXPAND_IN_STORAGE);
        rule.setLogExpiry(CLIENT_DEFAULT_LOG_EXPIRY);

        if ( httpHook.getStaticHeaders() != null ) {
            rule.addStaticHeaders(httpHook.getStaticHeaders());
        }


        if (!httpHook.getMethods().isEmpty()) {
            rule.setMethods(httpHook.getMethods().toArray(new String[httpHook.getMethods().size()]));
        }
    }

    /**
     * Creates an instance of the http client for this
     * request forwarder.
     */
    private void createHttpClient() {
        HttpClientOptions options = new HttpClientOptions().setDefaultHost(rule.getHost()).setDefaultPort(rule.getPort()).setMaxPoolSize(5000).setKeepAlive(true).setPipelining(false);
        if (rule.getScheme().equals("https")) {
            options.setSsl(true).setVerifyHost(false).setTrustAll(true);
        }
        client = vertx.createHttpClient(options);
    }

    /**
     * Handles the request (consumed) and forwards it
     * to the hook specific destination.
     * 
     * @param request - the original but already consumed request
     * @param requestBody - saved buffer with the data of body from the original request
     */
    public void forward(HttpServerRequest request, final Buffer requestBody) {

        // checking if the forwarder is for all methods
        if (httpHook.getMethods().isEmpty()) {
            forwarder.handle(request, requestBody);
        } else {
            // checking if the method from the request is handled by this forwarder
            if (httpHook.getMethods().contains(request.method().name())) {
                forwarder.handle(request, requestBody);
            }
        }
    }

    /**
     * Handles the request and forwards it
     * to the hook specific destination.
     * 
     * @param request request
     */
    public void forward(HttpServerRequest request) {
        forward(request, null);
    }

    /**
     * Closes the http client of the route.
     */
    public void cleanup() {
        vertx.setTimer(GRACE_PERIOD, new Handler<Long>() {
            public void handle(Long event) {
                LOG.debug("Cleaning up one client for route of " + urlPattern);
                client.close();
            }
        });
    }

    /**
     * Returns the hook associated
     * with this route.
     * 
     * @return the hook
     */
    public HttpHook getHook() {
        return httpHook;
    }
}
