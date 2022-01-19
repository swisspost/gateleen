package org.swisspush.gateleen.player.log;

import org.swisspush.gateleen.player.exchange.Exchange;
import com.google.common.base.Predicate;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects a request log live from a vert.x eventbus exposed with SockJS.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class EventBusCollector extends AbstractWebSocketHandler implements Collector {
    private String urlPrefix;
    private String sockPath;
    private String address;
    private BufferingRequestLog requestLog = new BufferingRequestLog();
    private Predicate<? super Exchange>  filter;
    private SockJsClient client;
    private WebSocketSession session;
    private int nbMessages = 0;

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    public EventBusCollector(String urlPrefix, String sockPath, String address, Predicate<? super Exchange>  filter) {
        this.urlPrefix = urlPrefix;
        this.sockPath = sockPath;
        this.address = address;
        this.filter = filter;
    }

    @Override
    public BufferingRequestLog getRequestLog() {
        return requestLog;
    }

    public void start() {
        if(client == null) {
            List<Transport> transports = new ArrayList<>(2);
            transports.add(new WebSocketTransport(new StandardWebSocketClient()));
            transports.add(new RestTemplateXhrTransport());

            client = new SockJsClient(transports);
            client.doHandshake(this, urlPrefix+sockPath);
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                // ignore
            }
            new Thread(() -> {
                while(client != null) {
                    try {
                        Thread.sleep(4000);
                        ping();
                    } catch (Exception e) {
                        log.error("Exception while pinging", e);
                        throw new RuntimeException(e);
                    }
                }
            }).start();
        }
    }

    private void ping() throws IOException {
        if(session != null && session.isOpen()) {
            session.sendMessage(new TextMessage("{\"type\":\"ping\"}"));
        } else {
            log.debug("Cannot ping, websocket session not yet open");
        }
    }

    public void stop() {
        log.debug("Stopping. Got {} messages", nbMessages);
        if(client != null) {
            client.stop();
        }
        client = null;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        nbMessages++;
        String line = new JSONObject(message.getPayload()).getString("body");
        requestLog.add(new Exchange(urlPrefix, new JSONObject(line)));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.debug("Web socket session established");
        this.session = session;
        session.sendMessage(new TextMessage("{\"type\":\"register\",\"address\":\""+address+"\"}"));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.debug("Websocket transport error", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.debug("Websocket session closed: {}", status.getReason());
    }
}
