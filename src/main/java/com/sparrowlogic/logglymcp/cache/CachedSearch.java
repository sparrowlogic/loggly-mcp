package com.sparrowlogic.logglymcp.cache;

import java.util.List;

import com.sparrowlogic.logglymcp.domain.LogEvent;

/**
 * A cached snapshot of a log search: the full events fetched once from Loggly, plus the metadata
 * needed to page through them and decide reuse. Held in memory by {@link SearchCacheService} for
 * the lifetime of the (single, long-lived) stdio server process.
 *
 * @param searchId      opaque id handed to clients for fetch_page / get_event
 * @param query         the Loggly query that produced this snapshot
 * @param fromResolved  resolved window start (ISO-8601 when resolvable, else the raw input)
 * @param untilResolved resolved window end (ISO-8601 when resolvable, else the raw input)
 * @param order         sort direction
 * @param events        the full events (complete payloads), bounded by the fetch cap
 * @param capped        true if Loggly held more events than were fetched
 * @param windowStable  true if the window is immutable (end older than the stable threshold)
 * @param createdAtMillis when the snapshot was fetched
 * @param expiresAtMillis when the snapshot should be evicted
 */
public record CachedSearch(
		String searchId,
		String query,
		String fromResolved,
		String untilResolved,
		String order,
		List<LogEvent> events,
		boolean capped,
		boolean windowStable,
		long createdAtMillis,
		long expiresAtMillis) {

	boolean isExpired(long nowMillis) {
		return nowMillis >= expiresAtMillis;
	}
}
