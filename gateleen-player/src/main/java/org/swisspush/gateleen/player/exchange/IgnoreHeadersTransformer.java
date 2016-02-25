package org.swisspush.gateleen.player.exchange;

import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import com.google.common.base.Function;

/**
 * Transforms an exchange into a new one where given headers are removed.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class IgnoreHeadersTransformer implements Function<Exchange, Exchange> {

    private String[] headers;

    public IgnoreHeadersTransformer(String... headers) {
        this.headers = headers;
    }

    @Override
    public Exchange apply(Exchange exchange) {
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(exchange.getRequest().getHeaders());
        HttpHeaders responseHeaders = new HttpHeaders();
        requestHeaders.putAll(exchange.getResponse().getHeaders());
        for (String header : headers) {
            requestHeaders.remove(header);
            responseHeaders.remove(header);
        }
        return new Exchange(new RequestEntity<>(exchange.getRequest().getBody(), requestHeaders, exchange.getRequest().getMethod(), exchange.getRequest().getUrl()),
                new ResponseEntity<>(exchange.getResponse().getBody(), responseHeaders, exchange.getResponse().getStatusCode()));
    }

    /**
     * Predefined transformer removing common headers unnecessary in comparisons:
     * <ul>
     * <li>connection</li>
     * <li>host</li>
     * <li>accept</li>
     * <li>user-agent</li>
     * <li>accept-encoding</li>
     * <li>content-length</li>
     * <li>x-client-timestamp</li>
     * <li>x-server-timestamp</li>
     * </ul>
     * 
     * @return Function
     */
    public static Function<Exchange, Exchange> ignoreCommonHeaders() {
        return new IgnoreHeadersTransformer("connection", "host", "accept", "user-agent", "accept-encoding", "content-length",
                "x-client-timestamp", "x-server-timestamp", "x-log", "x-hooked", "x-self-request");
    }
}
