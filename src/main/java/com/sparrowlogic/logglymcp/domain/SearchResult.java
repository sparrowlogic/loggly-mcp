package com.sparrowlogic.logglymcp.domain;

import java.util.List;

/**
 * One page of a cached log search. The full result set is fetched once and cached server-side
 * under {@code searchId}; this record is a window into it. Page through the snapshot with
 * {@code fetch_page(searchId, page)} and pull any event's full payload with
 * {@code get_event(searchId, id)} — neither re-queries Loggly.
 *
 * @param searchId     id of the cached snapshot — pass to fetch_page / get_event
 * @param page         zero-based page index of this response
 * @param pageSize     events per page
 * @param returned     number of events on this page
 * @param totalFetched total events in the cached snapshot
 * @param hasMore      whether further pages exist in the snapshot
 * @param nextPage     the next page index to request, or null when this is the last page
 * @param capped       true if Loggly held more events than the fetch cap — narrow the query for full coverage
 * @param cached       true if served from a pre-existing snapshot (a stable window re-queried), not a fresh fetch
 * @param windowStable true if the window end is old enough that the data is immutable (snapshot is reusable)
 * @param fromResolved the resolved start of the searched window (ISO-8601 when resolvable)
 * @param untilResolved the resolved end of the searched window (ISO-8601 when resolvable)
 * @param events       the compact events on this page
 */
public record SearchResult(
        String searchId,
        int page,
        int pageSize,
        int returned,
        int totalFetched,
        boolean hasMore,
        Integer nextPage,
        boolean capped,
        boolean cached,
        boolean windowStable,
        String fromResolved,
        String untilResolved,
        List<CompactEvent> events) {
}
