package com.sparrowlogic.logglymcp.domain;

import java.time.Instant;
import java.util.List;

/**
 * A small, token-bounded projection of a {@link LogEvent} returned on a search page. The bulky
 * parsed {@code event} tree and the full {@code raw} line are deliberately omitted — they are what
 * make raw Loggly responses huge — and {@code message} is truncated. Fetch the complete event with
 * {@code get_event(searchId, id)} when the detail is needed.
 *
 * @param id        event id — use with get_event for the full payload
 * @param timestamp event time in UTC epoch milliseconds
 * @param time      {@code timestamp} formatted as an ISO-8601 UTC instant, for readability
 * @param tags      source tags attached at ingest
 * @param logtypes  Loggly-derived log type tags
 * @param message   the log message (or raw line), truncated; full text via get_event
 */
public record CompactEvent(
        String id,
        long timestamp,
        String time,
        List<String> tags,
        List<String> logtypes,
        String message) {

    /** Projects a full {@link LogEvent} into a compact view, truncating the message to {@code maxChars}. */
    public static CompactEvent from(LogEvent e, int maxChars) {
        String text = e.logmsg();
        if (text == null || text.isBlank()) {
            text = e.raw();
        }
        String message = truncate(text, maxChars);
        String time = Instant.ofEpochMilli(e.timestamp()).toString();
        return new CompactEvent(e.id(), e.timestamp(), time, e.tags(), e.logtypes(), message);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int cut = maxChars;
        // Back off one char if the cut would fall between a UTF-16 surrogate pair (e.g. an
        // emoji), which would otherwise leave a lone surrogate in the returned string.
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + "… [truncated — use get_event for full text]";
    }
}
