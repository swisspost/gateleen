package org.swisspush.gateleen.player.exchange;

import com.google.common.base.Function;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * Transform an exchange into a new one by replacing all string pattern occurences in URL, headers and body.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class ReplaceStringTransformer implements Function<Exchange, Exchange> {

    private String pattern;
    private String with;

    /**
     * @param pattern Matches the string to replace.
     * @param with String used as substitution.
     */
    public ReplaceStringTransformer(String pattern, String with) {
        this.pattern = pattern;
        this.with = with;
    }

    @Override
    public Exchange apply(Exchange exchange) {
        try {
            return new Exchange(
                    new RequestEntity<>(
                            replace(exchange.getRequest().getBody()),
                            replace(exchange.getRequest().getHeaders()),
                            exchange.getRequest().getMethod(),
                            new URI(replace(exchange.getRequest().getUrl().toString()))),
                    new ResponseEntity<>(
                            replace(exchange.getResponse().getBody()),
                            replace(exchange.getResponse().getHeaders()),
                            exchange.getResponse().getStatusCode()));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpHeaders replace(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        HttpHeaders result = new HttpHeaders();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String value : entry.getValue()) {
                result.add(entry.getKey(), replace(value));
            }
        }
        return result;
    }

    private JSONObject replace(JSONObject body) {
        if (body == null) {
            return body;
        }
        try {
            return new JSONObject(replace(body.toString()));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private String replace(String input) {
        return input.replaceAll(pattern, with);
    }

    /**
     * Predefined transformer replacing all timestamps with the epoch (1970-01-01T00:00:00Z).
     * 
     * @return ReplaceStringTransformer
     */
    public static ReplaceStringTransformer clearTimestamps() {
        return new ReplaceStringTransformer("\\d{4}-[01]\\d-[0-3]\\dT[0-2]\\d:[0-5]\\d:[0-5]\\d(\\.\\d+)?([+-][0-2]\\d:[0-5]\\d|Z)", "1970-01-01T00:00:00Z");
    }

    /**
     * Predefined transformer placing all UUIDs with a blank one (00000000-0000-0000-0000-000000000000).
     * 
     * @return ReplaceStringTransformer
     */
    public static ReplaceStringTransformer clearUUIDs() {
        return new ReplaceStringTransformer("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "00000000-0000-0000-0000-000000000000");
    }
}
