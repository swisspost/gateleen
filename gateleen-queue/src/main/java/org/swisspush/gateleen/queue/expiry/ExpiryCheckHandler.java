package org.swisspush.gateleen.queue.expiry;

import io.vertx.core.http.CaseInsensitiveHeaders;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.joda.time.LocalDateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import org.swisspush.gateleen.core.http.HttpRequest;

/**
 * The ExpiryCheckHandler allows you to check the expiry
 * of a request.
 * It also offers methods for setting default
 * values for expiration and timestamps to the request header.
 *
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public final class ExpiryCheckHandler {
    public static final String SERVER_TIMESTAMP_HEADER = "X-Server-Timestamp";
    public static final String EXPIRE_AFTER_HEADER = "X-Expire-After";
    public static final String QUEUE_EXPIRE_AFTER_HEADER = "x-queue-expire-after";

    private static Logger log = LoggerFactory.getLogger(ExpiryCheckHandler.class);

    private static DateTimeFormatter dfISO8601 = ISODateTimeFormat.dateTime().withZone(DateTimeZone.forID("Europe/Zurich"));
    private static DateTimeFormatter dfISO8601Parser = ISODateTimeFormat.dateTimeParser().withZone(DateTimeZone.forID("Europe/Zurich"));
    private static DateTimeFormatter isoDateTimeParser = ISODateTimeFormat.dateTimeParser();

    private ExpiryCheckHandler() {
        // Prevent instantiation
    }

    /**
     * Checks if a "X-Server-Timestamp" header is set or not.
     * If no valid value is found, the actual timestamp is set.
     * 
     * @param request request
     */
    public static void updateServerTimestampHeader(HttpRequest request) {
        if (request.getHeaders() == null) {
            request.setHeaders(new CaseInsensitiveHeaders());
        }

        updateServerTimestampHeader(request.getHeaders());
    }

    /**
     * Checks if a "X-Server-Timestamp" header is set or not.
     * If no valid value is found, the actual timestamp is set.
     * 
     * @param request request
     */
    public static void updateServerTimestampHeader(HttpServerRequest request) {
        updateServerTimestampHeader(request.headers());
    }

    /**
     * Checks if a "X-Server-Timestamp" header is set or not.
     * If no valid value is found, the actual timestamp is set.
     * 
     * @param headers headers
     */
    public static void updateServerTimestampHeader(MultiMap headers) {
        String serverTimestamp = headers.get(SERVER_TIMESTAMP_HEADER);

        if (serverTimestamp == null) {
            String nowAsISO = dfISO8601.print(Instant.now());

            log.debug("Setting " + SERVER_TIMESTAMP_HEADER + " value to " + nowAsISO + "  since header " + SERVER_TIMESTAMP_HEADER + " is not defined");

            headers.set(SERVER_TIMESTAMP_HEADER, nowAsISO);
        } else {
            String updatedTimestamp = localizeTimestamp(serverTimestamp);

            if (!updatedTimestamp.equals(serverTimestamp)) {
                log.debug("Updating" + SERVER_TIMESTAMP_HEADER + " value from " + serverTimestamp + "  to " + updatedTimestamp);

                headers.remove(SERVER_TIMESTAMP_HEADER);
                headers.set(SERVER_TIMESTAMP_HEADER, updatedTimestamp);
            }
        }
    }

    /**
     * Extracts the value of the "X-Expire-After" header.
     * If the value can't be extracted (not found, invalid, and so on),
     * null is returned.
     * 
     * @param headers headers
     * @return expire-after time in seconds or null if nothing is found
     */
    public static Integer getExpireAfter(MultiMap headers) {
        return getExpireValue(headers, EXPIRE_AFTER_HEADER);
    }

    /**
     * Extracts the value of the "x-queue-expire-after" header.
     * If the value can't be extracted (not found, invalid, and so on),
     * null is returned.
     *
     * @param headers headers
     * @return queue-expire-after time in seconds or null if nothing is found
     */
    public static Integer getQueueExpireAfter(MultiMap headers) {
        return getExpireValue(headers, QUEUE_EXPIRE_AFTER_HEADER);
    }

    /**
     * Extracts the value of the given header field.
     * If the value can't be extracted (not found, invalid, and so on),
     * null is returned.
     *
     * @param headers headers
     * @param field the header field
     * @return expire-after time in seconds or null if nothing is found
     */
    private static Integer getExpireValue(MultiMap headers, String field) {
        String expireHeaderValue = headers.get(field);

        Integer value;

        // no header set for the given field
        if (expireHeaderValue == null) {
            log.debug(field + " not defined");
            value = null;
        } else {
            try {
                value = Integer.parseInt(expireHeaderValue);

                // redis returns an error if setex is called with negativ values
                if (value < 0) {
                    log.debug(field + " is a negative number: " + expireHeaderValue);
                    value = null;
                } else {
                    log.debug("Setting " + field + " value to " + value + " seconds as defined in header " + field);
                }
            } catch (Exception e) {
                log.warn(field + " is not a number: " + expireHeaderValue);
                value = null;
            }
        }

        return value;
    }

    /**
     * Checks if the given request has expired or not.
     * If no "X-Expire-After" or "X-Server-Timestamp" is found <code>false</code> is returned.
     * 
     * @param request request
     * @return true if the request has expired, false otherwise
     */
    public static boolean isExpired(HttpRequest request) {
        return isExpired(request.getHeaders());
    }

    /**
     * Checks if the given request has expired or not.
     * If no "X-Expire-After" or "X-Server-Timestamp" is found <code>false</code> is returned.
     * 
     * @param request request
     * @return true if the request has expired, false otherwise
     */
    public static boolean isExpired(HttpServerRequest request) {
        return isExpired(request.headers());
    }

    /**
     * Checks the expiration based on the given headers.
     * If "X-Expire-After" or "x-queue-expire-after" and
     * no "X-Server-Timestamp" is found <code>false</code>
     * is returned.
     * 
     * @param headers headers
     * @return true if the request has expired, false otherwise
     */
    public static boolean isExpired(MultiMap headers) {
        if (headers != null) {

            Integer queueExpireAfter = getQueueExpireAfter(headers);
            Integer expireAfter = getExpireAfter(headers);

            String serverTimestamp = headers.get(SERVER_TIMESTAMP_HEADER);

            // override expireAfter if x-queue-expire-after header is set
            if ( queueExpireAfter != null ) {
                expireAfter = queueExpireAfter;
            }

            if (serverTimestamp != null && expireAfter != null) {
                LocalDateTime timestamp = parseDateTime(serverTimestamp);
                LocalDateTime expirationTime = getExpirationTime(timestamp, expireAfter);
                LocalDateTime now = getActualTime();

                log.debug(" > isExpired - timestamp " + timestamp + " | expirationTime " + expirationTime + " | now " + now);

                // request expired?
                return expirationTime.isBefore(now);
            }
        }

        return false;
    }

    /**
     * Checks the expiration based on the given headers and a timstamp in milliseconds.
     *
     * @param headers headers
     * @return true if the request has expired, false otherwise
     */
    public static boolean isExpired(MultiMap headers, Long timestamp) {
        if (headers != null && timestamp != null) {

            Integer queueExpireAfter = getQueueExpireAfter(headers);
            Integer expireAfter = getExpireAfter(headers);

            // override expireAfter if x-queue-expire-after header is set
            if ( queueExpireAfter != null ) {
                expireAfter = queueExpireAfter;
            }

            if (expireAfter != null) {
                long expiredSince = System.currentTimeMillis() - ( timestamp + expireAfter * 1000 );

                if(expiredSince > 0) {
                    log.debug(" > isExpired - Request expired since {} milliseconds.", expiredSince);
                    return true;
                } else {
                    log.debug(" > isExpired - Request not expired (would expire in {} milliseconds).", -expiredSince);
                    return false;
                }

            }
        }

        return false;
    }

    /**
     * Returns the actual datetime.
     * 
     * @return LocalDateTime
     */
    public static LocalDateTime getActualTime() {
        return dfISO8601Parser.parseLocalDateTime(dfISO8601.print(Instant.now()));
    }

    /**
     * Returns the expiration time, based on the actual time
     * in addition with the expireAfter value.
     * 
     * @param expireAfter - in seconds
     * @return expiration time
     */
    public static LocalDateTime getExpirationTime(int expireAfter) {
        return getExpirationTime(getActualTime(), expireAfter);
    }

    /**
     * Returns the expiration time based on the given timestamp
     * in addition with the expireAfter value.
     * 
     * @param serverTimestamp serverTimestamp
     * @param expireAfter - in seconds
     * @return expiration time.
     */
    public static LocalDateTime getExpirationTime(String serverTimestamp, int expireAfter) {
        return getExpirationTime(parseDateTime(serverTimestamp), expireAfter);
    }

    /**
     * Parses the given string to a LocalDateTime object.
     * 
     * @param datetime datetime
     * @return LocalDateTime
     */
    public static LocalDateTime parseDateTime(String datetime) {
        return dfISO8601Parser.parseLocalDateTime(localizeTimestamp(datetime));
    }

    /**
     * Prints the given datetime as a string.
     * 
     * @param datetime datetime
     * @return String
     */
    public static String printDateTime(LocalDateTime datetime) {
        return dfISO8601.print(datetime);
    }

    /**
     * Returns the expiration time.
     * 
     * @param timestamp timestamp
     * @param expireAfter expireAfter
     * @return expiration time.
     */
    private static LocalDateTime getExpirationTime(LocalDateTime timestamp, int expireAfter) {
        LocalDateTime expirationTime = timestamp.plusSeconds(expireAfter);

        log.debug("getExpirationTime: " + expirationTime);

        return expirationTime;
    }

    /**
     * Sets an "x-queue-expire-after" header.
     * If such a header already exist, it's overridden by the new value.
     *
     * @param request request
     * @param queueExpireAfter expireAfter
     */
    public static void setQueueExpireAfter(HttpRequest request, int queueExpireAfter) {
        if (request.getHeaders() == null) {
            request.setHeaders(new CaseInsensitiveHeaders());
        }

        setFieldValue(request.getHeaders(), QUEUE_EXPIRE_AFTER_HEADER, queueExpireAfter);
    }

    /**
     * Sets an "X-Expire-After" header.
     * If such a header already exist, it's overridden by the new value.
     * 
     * @param request request
     * @param expireAfter expireAfter
     */
    public static void setExpireAfter(HttpRequest request, int expireAfter) {
        if (request.getHeaders() == null) {
            request.setHeaders(new CaseInsensitiveHeaders());
        }

        setFieldValue(request.getHeaders(), EXPIRE_AFTER_HEADER, expireAfter);
    }

    /**
     * Sets an "x-queue-expire-after" header.
     * If such a header already exist, it's overridden by the new value.
     *
     * @param request request
     * @param queueExpireAfter expireAfter
     */
    public static void setQueueExpireAfter(HttpServerRequest request, int queueExpireAfter) {
        setFieldValue(request.headers(), QUEUE_EXPIRE_AFTER_HEADER, queueExpireAfter);
    }

    /**
     * Sets an "X-Expire-After" header.
     * If such a header already exist, it's overridden by the new value.
     * 
     * @param request request
     * @param expireAfter expireAfter
     */
    public static void setExpireAfter(HttpServerRequest request, int expireAfter) {
        setFieldValue(request.headers(), EXPIRE_AFTER_HEADER, expireAfter);
    }

    /**
     * Sets an "x-queue-expire-after" header.
     * If such a header already exist, it's overridden by the new value.
     *
     * @param headers headers
     * @param queueExpireAfter queueExpireAfter
     */
    public static void setQueueExpireAfter(MultiMap headers, int queueExpireAfter) {
        setFieldValue(headers, QUEUE_EXPIRE_AFTER_HEADER, queueExpireAfter);
    }

    /**
     * Sets an "X-Expire-After" header.
     * If such a header already exist, it's overridden by the new value.
     *
     * @param headers headers
     * @param expireAfter expireAfter
     */
    public static void setExpireAfter(MultiMap headers, int expireAfter) {
        setFieldValue(headers, EXPIRE_AFTER_HEADER, expireAfter);
    }

    /**
     * Sets a header with the given field name and the given expire value.
     * If such a header already exist, it's overridden by the new value.

     * @param headers headers
     * @param field the name of the header field
     * @param expireValue expireValue
     */
    private static void setFieldValue( MultiMap headers, String field, int expireValue ) {
        if (headers.get(field) != null) {
            headers.remove(field);
        }

        headers.set(field, String.valueOf(expireValue));
    }

    /**
     * Transform a timestamp to local time timestamp it is UTC.
     * If the timestamp is not parsable, do nothing.
     *
     * @param timestamp - the timestamp
     * @return String
     */
    private static String localizeTimestamp(String timestamp) {
        String localizedTimestamp = timestamp;
        if (localizedTimestamp != null && localizedTimestamp.toUpperCase().endsWith("Z")) {
            try {
                DateTime dt = isoDateTimeParser.parseDateTime(localizedTimestamp);
                localizedTimestamp = dfISO8601.print(dt);
            } catch (IllegalArgumentException e) {
                log.warn("Could not parse: " + localizedTimestamp);
            }
        }

        return localizedTimestamp;
    }
}