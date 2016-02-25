package org.swisspush.gateleen.player.exchange;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

/**
 * Obtain the elapsed time relatively to a reference.
 *
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class TimeResolver {
    private static final DateTimeFormatter parser = ISODateTimeFormat.dateTimeParser();
    private long reference;

    public TimeResolver(String referenceIsoTimestamp) {
        reference = parser.parseDateTime(referenceIsoTimestamp).getMillis();
    }

    public long resolve(String isoTimestamp) {
        DateTime t = parser.parseDateTime(isoTimestamp);
        return t.getMillis() - reference;
    }
}
