package org.swisspush.gateleen.queue.expiry;

import io.vertx.core.MultiMap;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpServerRequest;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.http.HttpRequest;

import java.util.Optional;

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

    private ExpiryCheckHandler() {
        // Prevent instantiation
    }

    /**
     * Checks if a "X-Server-Timestamp" header is set or not.
     * If no valid value is found, the current timestamp is set.
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
     * If no valid value is found, the current timestamp is set.
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
            headers.remove(SERVER_TIMESTAMP_HEADER);
            headers.set(SERVER_TIMESTAMP_HEADER, printDateTime(DateTime.now()));
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
        try {
            Integer ans = getExpireValue(headers.get(EXPIRE_AFTER_HEADER));
            // Convert -1 to null
            return (ans != null && ans == -1) ? null : ans;
        } catch (NumberFormatException e) {
            // Treat corrupt header same as it were not set at all.
            // This is to keep backward compatibility to previous version of getExpireValue(MultiMap,String).
            return null;
        }
    }

    /**
     * Delegates to {@link #getExpireValue(String)}
     *
     * This is extended version of {@link #getExpireAfter(MultiMap)} but also
     * returning -1 for infinite.
     *
     * @param headers
     *      Headers to fetch value from.
     * @return
     *      <p>The parsed expire-after value.</p>
     *      <p>Returns empty in case {@link #getExpireValue(String)} returned null.</p>
     */
    public static Optional<Integer> getExpireAfterConcerningCaseOfCorruptHeaderAndInfinite(MultiMap headers) {
        try {
            final Integer expireValue = getExpireValue(headers.get(EXPIRE_AFTER_HEADER));
            return Optional.ofNullable(expireValue);
        } catch (NumberFormatException e) {
            // Throwing exceptions is not allowed by design.
            // See: "https://github.com/swisspush/gateleen/pull/209#discussion_r177712751"
            log.warn("Conceal exception (because we're not allowed to throw by design): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
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
        try {
            final Integer ans = getExpireValue(headers.get(QUEUE_EXPIRE_AFTER_HEADER));
            // Convert -1 to null
            return (ans != null && ans == -1) ? null : ans;
        } catch (NumberFormatException e) {
            // Treat corrupt header same as it were not set at all.
            // This is to keep backward compatibility to previous version of getExpireValue(MultiMap,String).
            return null;
        }
    }

    /**
     * @param expirationTimeout
     *      Value as provided in the X-Expire-After header for example.
     * @return
     *      Expire-after time in seconds or null if header not present. This
     *      possibly returns value -1 indicating "never expires".
     * @throws NumberFormatException
     *      In case the value contained in the header is not a valid
     *      'X-Expire-After' value. A negative number or a non-numeric value
     *      for example.
     */
    private static Integer getExpireValue( final String expirationTimeout ) throws NumberFormatException {
        final Integer ans;
        if (expirationTimeout == null) {
            // If we get null we'll answer with null.
            ans = null;
        } else {
            // Value exists. Try parsing it (this will throw specified exception on failure).
            ans = Integer.parseInt(expirationTimeout);
            // Further, treat numbers below -1 as invalid. -1 itself is accepted indicating
            // "infinite".
            if (ans < -1) {
                throw new NumberFormatException("Values less than -1 aren't valid expireAfter values.");
            }
        }
        return ans;
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
                long expiredSince = System.currentTimeMillis() - ( timestamp + expireAfter * 1000L );

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
     * Returns the expiration time, based on the current time (now)
     * in addition with the expireAfter value.
     * 
     * @param expireAfter - in seconds
     * @return expiration time
     */
    public static DateTime getExpirationTime(int expireAfter) {
        return getExpirationTime(DateTime.now(), expireAfter);
    }

    /**
     * @return
     *      Expiration time based on passed expireAfter or empty in case of an infinite
     *      expiration.
     */
    public static Optional<String> getExpirationTimeAsString(int expireAfter) {
        final String expirationTime;
        if (expireAfter == -1) {
            expirationTime = null;
        } else {
            expirationTime = printDateTime(getExpirationTime(expireAfter));
        }
        return Optional.ofNullable(expirationTime);
    }

    /**
     * Parses the given string to a DateTime object.
     * 
     * @param datetime datetime as string
     * @return DateTime
     */
    public static DateTime parseDateTime(String datetime) {
        return dfISO8601Parser.parseDateTime(datetime);
    }

    /**
     * Prints the given datetime as a string.
     * 
     * @param datetime datetime
     * @return String
     */
    public static String printDateTime(DateTime datetime) {
        return dfISO8601.print(datetime);
    }

    /**
     * Returns the expiration time.
     * 
     * @param timestamp timestamp
     * @param expireAfter expireAfter
     * @return expiration time.
     */
    private static DateTime getExpirationTime(DateTime timestamp, int expireAfter) {
        DateTime expirationTime = timestamp.plusSeconds(expireAfter);

        log.debug("getExpirationTime: " + expirationTime);

        return expirationTime;
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
}