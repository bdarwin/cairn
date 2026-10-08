package io.github.bdarwin.cairn.internal.http;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** The two date forms S3 uses: HTTP dates in headers, ISO 8601 with milliseconds in XML. */
final class HttpDates {

    private static final DateTimeFormatter HTTP = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC);

    private HttpDates() {
    }

    static String http(Instant t) {
        return HTTP.format(t);
    }

    static String iso(Instant t) {
        return ISO.format(t.truncatedTo(ChronoUnit.MILLIS));
    }

    /** Parses an HTTP date (RFC 1123 form); null if it is not one. */
    static Instant parseHttp(String s) {
        if (s == null) return null;
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
