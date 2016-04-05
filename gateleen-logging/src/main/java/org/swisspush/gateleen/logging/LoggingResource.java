package org.swisspush.gateleen.logging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class representing the json logging resource
 *
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class LoggingResource {

    /**
     * Logging strategy for the headers
     */
    public enum HeaderLogStrategy {
        LOG_ALL, LOG_NONE, LOG_LIST
    }

    private HeaderLogStrategy headerLogStrategy = HeaderLogStrategy.LOG_ALL;

    private List<String> headers = new ArrayList<>();

    private List<Map<String, String>> payloadFilters = new ArrayList<>();
    private Map<String, Map<String, String>> destinationEntries = new HashMap<>();

    /**
     * Returns the strategy for the headers logging. Default value is {@link HeaderLogStrategy#LOG_ALL}
     * 
     * @return the current strategy for the headers logging
     */
    public HeaderLogStrategy getHeaderLogStrategy() {
        if (headerLogStrategy == null) {
            headerLogStrategy = HeaderLogStrategy.LOG_ALL;
        }
        return headerLogStrategy;
    }

    /**
     * Set the strategy for the headers logging
     * 
     * @param headerLogStrategy HeaderLogStrategy enum
     */
    public void setHeaderLogStrategy(HeaderLogStrategy headerLogStrategy) {
        this.headerLogStrategy = headerLogStrategy;
    }

    /**
     * Returns a list of header names to log. Can be empty.
     * 
     * @return a list of header names to log
     */
    public List<String> getHeaders() {
        if (headers == null) {
            headers = new ArrayList<String>();
        }
        return headers;
    }

    /**
     * Adds a list of header names to log
     * 
     * @param headers list of headers
     */
    public void addHeaders(List<String> headers) {
        getHeaders().addAll(headers);
    }

    /**
     * Returns a list of payload filters
     * 
     * @return list of payload filters
     */
    public final List<Map<String, String>> getPayloadFilters() {
        if (payloadFilters == null) {
            Map<String, String> filters = new HashMap<>();
            payloadFilters = new ArrayList<>();
            payloadFilters.add(filters);
        }
        return payloadFilters;
    }

    /**
     * Returns a map with destinations
     * 
     * @return map with destinations
     */
    public final Map<String, Map<String, String>> getDestinationEntries() {
        if (destinationEntries == null) {
            destinationEntries = new HashMap<>();
        }
        return destinationEntries;
    }

    /**
     * Clears all logging resource values like payload filters and headers
     */
    public void reset() {
        getHeaders().clear();
        getPayloadFilters().clear();
        getDestinationEntries().clear();
    }

    /**
     * Adds a payloadfilter to the logging resource
     * 
     * @param payloadFilter payloadFilter to add
     */
    public void addPayloadFilter(Map<String, String> payloadFilter) {
        getPayloadFilters().add(payloadFilter);
    }

    /**
     * Adds destination entries for the payloadFilter to the logging resources.
     * 
     * @param destinationEntries entries for the payloadFilter
     */
    public void addFilterDestinations(Map<String, Map<String, String>> destinationEntries) {
        getDestinationEntries().putAll(destinationEntries);
    }
}
