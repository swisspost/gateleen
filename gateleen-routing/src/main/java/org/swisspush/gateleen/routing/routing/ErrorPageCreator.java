package org.swisspush.gateleen.routing.routing;

import org.swisspush.gateleen.core.util.StringUtils;
import com.floreysoft.jmte.Engine;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class to static html error pages
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ErrorPageCreator {

    private static final String ROUTING_BROKEN_TEMPLATE = "<!DOCTYPE html>\n"
            + "<html>\n"
            + "    <head><h1 style=\"color: red;\">Routing is broken</h1></head>\n"
            + "    <body>\n"
            + "        <p>Message: ${message}</p>\n"
            + "        <p style=\"display: ${display};\">Fix it under <a href=\"${url}\">${urltext}</a> </p>\n"
            + "    </body>\n"
            + "</html>";

    /**
     * Creates the static 'Routing Broken' HTML error page.
     *
     * @param message the message to display
     * @param url the url for the resource
     * @param urltext the text displayed as link text
     * @return String
     */
    public static String createRoutingBrokenHTMLErrorPage(String message, String url, String urltext) {

        if (StringUtils.isEmpty(message)) {
            message = "Not provided!";
        }

        Engine engine = new Engine();
        Map<String, Object> model = new HashMap<>();
        model.put("message", message);
        model.put("url", url);
        model.put("urltext", urltext);

        String display = "none";
        if (StringUtils.isNotEmpty(url) && StringUtils.isNotEmpty(urltext)) {
            display = "block";
        }
        model.put("display", display);

        return engine.transform(ROUTING_BROKEN_TEMPLATE, model);
    }
}
