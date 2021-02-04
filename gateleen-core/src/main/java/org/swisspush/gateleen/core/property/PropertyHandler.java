/*
 * ------------------------------------------------------------------------------------------------
 * Copyright 2016 by Swiss Post, Information Technology Services
 * ------------------------------------------------------------------------------------------------
 * $Id$
 * ------------------------------------------------------------------------------------------------
 */
package org.swisspush.gateleen.core.property;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.refresh.Refreshable;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.StatusCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Allows you to register properties, which can be
 * updated in realtime by a PUT request to the
 * communication server. <br>
 * Eg.:
 * 
 * <pre>
 * // Create a new instance 
 * PropertyHandler pHandler = new PropertyHandler(props);
 * 
 * // Register refreshables
 * pHandler.addRefreshable(refreshable)
 * 
 * // Add a property
 * pHandler.addProperty("/car/v1/id","carId", "car.id")
 * 
 * // Update the property 'car.id' 
 * PUT /car/v1/id
 *  {
 *      "carId" : "mynewValue"
 *  }
 * </pre>
 * 
 * @author ljucam
 */
public class PropertyHandler {
    private Logger log = LoggerFactory.getLogger(PropertyHandler.class);

    private final Map<String, Object> props;
    private final List<Refreshable> refreshables;
    private final Map<String, Map<String, String>> propertyUrls;
    private final String serverUri;

    /**
     * Creates a new instance of the PropertyHandler with the
     * given property store as a parameter.
     * 
     * @param props - the property map
     */
    public PropertyHandler(String serverUri, Map<String, Object> props) {
        this.serverUri = serverUri;
        this.props = props;
        refreshables = new ArrayList<>();
        propertyUrls = new HashMap<>();
    }

    /**
     * Adds a new Refreshable. <br >
     * All refreshables will be refreshed, if a
     * property changes.
     * 
     * @param refreshable - an instance of Refreshable
     */
    public void addRefreshable(Refreshable refreshable) {
        refreshables.add(refreshable);
    }

    /**
     * Assigns the property name to an id value of a given url. <br >
     * You can add for example:
     * 
     * <pre>
     * addProperty("/vehicle/v1/id", "identityId", "identity.id")
     * addProperty("/vehicle/v1/id", "vehicleId", "vehicle.id")
     * </pre>
     * 
     * @param url
     * @param value
     * @param propertyName
     */
    public void addProperty(String url, String value, String propertyName) {
        log.info("register property handler for property '" + propertyName + "' on url '" + url + "' and value id '" + value + "'.");
        String adaptedUrl = serverUri + url;

        if (propertyUrls.containsKey(adaptedUrl)) {
            propertyUrls.get(adaptedUrl).put(value, propertyName);
        } else {
            Map<String, String> map = new HashMap<>();
            map.put(value, propertyName);
            propertyUrls.put(adaptedUrl, map);
        }
    }

    /**
     * Checks if the request must be handeld by the PropertyHandler. If so, the return value
     * will be <code>true</code> otherwise it will be <code>false</code>.
     * 
     * @param request - the original request
     * @return true if the handler processes the request, false otherwise
     */
    public boolean handle(final HttpServerRequest request) {
        // Only process PUT requests and request, which URL can be found
        if (request.method().equals(HttpMethod.PUT) && propertyUrls.containsKey(request.uri())) {
            log.info("Got a request to update propertyUrl=[{}]", request.uri());
            // process body
            request.bodyHandler(buffer -> {
                Map<String, String> idProperties = propertyUrls.get(request.uri());
                JsonObject body = new JsonObject(buffer.toString());

                boolean found = false;
                for (String keyId : idProperties.keySet()) {

                    // try to find a watched key in the body
                    if (body.containsKey(keyId)) {
                        // save the value in the properties
                        props.put(idProperties.get(keyId), body.getValue(keyId));

                        // refresh all refreshables
                        refresh();

                        log.info("Updated property=[{}] with value=[{}] triggered by propertyUrl=[{}]", keyId, body.getValue(keyId), request.uri());

                        // break the process
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    log.warn("id for the request PUT " + request.uri() + " could not be found: " + body.toString());
                }

                // everythin is fine
                ResponseStatusCodeLogUtil.info(request, StatusCode.OK, PropertyHandler.class);
                request.response().setStatusCode(StatusCode.OK.getStatusCode());
                request.response().setStatusMessage(StatusCode.OK.getStatusMessage());
                request.response().end();
            });

            return true;
        }

        return false;
    }

    /**
     * Refreshes all refreshables.
     */
    private void refresh() {
        for (Refreshable refreshable : refreshables) {
            refreshable.refresh();
        }
    }
}
