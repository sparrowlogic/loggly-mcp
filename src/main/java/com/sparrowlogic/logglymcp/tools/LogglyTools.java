package com.sparrowlogic.logglymcp.tools;

import com.sparrowlogic.logglymcp.cache.CachedSearch;
import com.sparrowlogic.logglymcp.cache.SearchCacheService;
import com.sparrowlogic.logglymcp.client.LogglyClient;
import com.sparrowlogic.logglymcp.config.LogglyProperties;
import com.sparrowlogic.logglymcp.domain.CompactEvent;
import com.sparrowlogic.logglymcp.domain.LogEvent;
import com.sparrowlogic.logglymcp.domain.SearchResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * MCP tools exposing read-only Loggly log search. Discovered automatically by the MCP server's
 * annotation scanner (no manual {@code ToolCallbackProvider} bean required).
 *
 * <p>Log aggregation returns large result sets, so search is split into fetch-once-then-paginate:
 * {@code search_logs} fetches and caches a snapshot and returns compact page 0; {@code fetch_page}
 * pages the snapshot; {@code get_event} pulls one event's full payload. Only {@code search_logs}
 * calls Loggly's events API — the other two read the cache.
 */
@Component
public class LogglyTools {

    private final SearchCacheService cache;
    private final LogglyClient client;
    private final LogglyProperties properties;

    public LogglyTools(SearchCacheService cache, LogglyClient client, LogglyProperties properties) {
        this.cache = cache;
        this.client = client;
        this.properties = properties;
    }

    @McpTool(name = "search_logs",
            description = "Search Loggly logs. Fetches the matching events once, caches them under a "
                    + "returned 'searchId', and returns the first page as COMPACT events (id, time, "
                    + "tags, logtypes, truncated message). Page through the rest with "
                    + "fetch_page(searchId, page) and get any event's full payload with "
                    + "get_event(searchId, id) — neither re-queries Loggly. 'query' uses Loggly search "
                    + "syntax (e.g. 'json.level:ERROR', '\"connection refused\"', 'tag:prod'); omit or "
                    + "pass '*' for everything. Time bounds accept relative spans with a leading minus "
                    + "('-24h', '-7d'), bare dates ('2026-06-01'), ISO-8601 timestamps, or 'now' "
                    + "(defaults: from=-24h, until=now). Newest-first by default. If 'capped' is true, "
                    + "more events existed than were fetched — narrow the query or window.")
    public SearchResult searchLogs(
            @McpToolParam(description = "Loggly search query. Optional; defaults to '*' (all events).",
                    required = false)
            String query,
            @McpToolParam(description = "Start of the window. Optional; defaults to -24h. "
                    + "e.g. -24h, -7d, 2026-06-01, or an ISO-8601 timestamp.", required = false)
            String from,
            @McpToolParam(description = "End of the window. Optional; defaults to 'now'.",
                    required = false)
            String until,
            @McpToolParam(description = "Sort direction: 'desc' (newest first, default) or 'asc'.",
                    required = false)
            String order,
            @McpToolParam(description = "Events per page (default 25, max 200).", required = false)
            Integer pageSize) {
        SearchCacheService.Handle handle = this.cache.search(query, from, until, order);
        return this.page(handle.search(), 0, this.pageSize(pageSize), handle.reused());
    }

    @McpTool(name = "fetch_page",
            description = "Return a page from a cached search snapshot created by search_logs. Does "
                    + "not re-query Loggly. Use the 'searchId' and 'nextPage' from a prior result to "
                    + "page through; 'hasMore' indicates whether further pages exist. Snapshots expire "
                    + "after a few minutes — if the searchId is unknown, run search_logs again.")
    public SearchResult fetchPage(
            @McpToolParam(description = "The searchId returned by search_logs.")
            String searchId,
            @McpToolParam(description = "Zero-based page index to return.")
            Integer page,
            @McpToolParam(description = "Events per page (default 25, max 200). Optional.",
                    required = false)
            Integer pageSize) {
        CachedSearch snapshot = this.cache.get(searchId);
        return this.page(snapshot, page == null ? 0 : Math.max(0, page), this.pageSize(pageSize), true);
    }

    @McpTool(name = "get_event",
            description = "Return the COMPLETE payload of one event (full message, raw line and the "
                    + "parsed structure) from a cached search snapshot, by its event id. Reads the "
                    + "cache; does not re-query Loggly. Use after search_logs/fetch_page to drill into "
                    + "an event whose compact message was truncated.")
    public LogEvent getEvent(
            @McpToolParam(description = "The searchId returned by search_logs.")
            String searchId,
            @McpToolParam(description = "The event id (the 'id' field of a compact event).")
            String eventId) {
        CachedSearch snapshot = this.cache.get(searchId);
        return snapshot.events().stream()
                .filter(e -> e.id() != null && e.id().equals(eventId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Event '" + eventId + "' is not in snapshot '" + searchId + "'."));
    }

    @McpTool(name = "list_fields",
            description = "List the log fields available for a query and time window, each with its "
                    + "most common values and counts — a discovery aid for building search_logs queries. "
                    + "Time bounds follow the same syntax as search_logs.")
    public JsonNode listFields(
            @McpToolParam(description = "Loggly search query to scope the fields. Optional; defaults "
                    + "to '*'.", required = false)
            String query,
            @McpToolParam(description = "Start of the window. Optional; defaults to -24h.",
                    required = false)
            String from,
            @McpToolParam(description = "End of the window. Optional; defaults to 'now'.",
                    required = false)
            String until,
            @McpToolParam(description = "Number of fields to return (default 10, max 500).",
                    required = false)
            Integer facetSize) {
        return this.client.listFields(query, from, until, facetSize == null ? 0 : facetSize);
    }

    @McpTool(name = "field_values",
            description = "Return the distinct values of a single log field with their occurrence "
                    + "counts (a facet) for a query and time window — e.g. the top values of "
                    + "'syslog.host' or 'json.level'. Use list_fields first to discover field names. "
                    + "Time bounds follow the same syntax as search_logs.")
    public JsonNode fieldValues(
            @McpToolParam(description = "Field name to facet on, e.g. 'syslog.host' or 'json.level'.")
            String field,
            @McpToolParam(description = "Loggly search query to scope the values. Optional; defaults "
                    + "to '*'.", required = false)
            String query,
            @McpToolParam(description = "Start of the window. Optional; defaults to -24h.",
                    required = false)
            String from,
            @McpToolParam(description = "End of the window. Optional; defaults to 'now'.",
                    required = false)
            String until,
            @McpToolParam(description = "Number of values to return (default 50, max 300).",
                    required = false)
            Integer facetSize) {
        return this.client.fieldValues(field, query, from, until, facetSize == null ? 0 : facetSize);
    }

    /** Builds one {@link SearchResult} page (compact events) from a cached snapshot. */
    private SearchResult page(CachedSearch s, int page, int pageSize, boolean cached) {
        int total = s.events().size();
        int start = Math.min(page * pageSize, total);
        int end = Math.min(start + pageSize, total);
        List<CompactEvent> events = s.events().subList(start, end).stream()
                .map(e -> CompactEvent.from(e, this.properties.messageMaxChars()))
                .toList();
        boolean hasMore = end < total;
        return new SearchResult(
                s.searchId(), page, pageSize, events.size(), total,
                hasMore, hasMore ? page + 1 : null,
                s.capped(), cached, s.windowStable(),
                s.fromResolved(), s.untilResolved(),
                events);
    }

    private int pageSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return this.properties.defaultPageSize();
        }
        return Math.min(requested, this.properties.maxPageSize());
    }
}
