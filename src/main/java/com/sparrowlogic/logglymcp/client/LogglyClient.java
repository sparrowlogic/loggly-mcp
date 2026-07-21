package com.sparrowlogic.logglymcp.client;

import com.sparrowlogic.logglymcp.domain.LogEvent;
import com.sparrowlogic.logglymcp.domain.SearchPage;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Read-only client for the Loggly event-retrieval API, built on {@link RestClient}.
 *
 * <p>Centred on the paginating endpoint {@code /apiv2/events/iterate}, which returns a page of
 * events plus an opaque {@code next} cursor URL. {@link #searchAll} eagerly walks those cursors
 * up to a cap and returns the whole batch in one {@link SearchPage} (its {@code next} non-null
 * signals the cap was hit), so callers can cache and paginate a stable snapshot rather than
 * following short-lived cursors mid-scroll. Discovery uses the {@code /apiv2/fields/} endpoints.
 */
@Component
public class LogglyClient {

    /** A Loggly iterate page maxes out at 1000 events. */
    private static final int LOGGLY_MAX_PAGE = 1000;

    private final RestClient rest;

    public LogglyClient(RestClient logglyRestClient) {
        this.rest = logglyRestClient;
    }

    /**
     * Fetch up to {@code maxFetch} events for a search, following Loggly's {@code next} cursors as
     * needed. Returns a {@link SearchPage} whose {@code events} hold the full batch and whose
     * {@code next} is non-null only if more events remained at the cap (i.e. the result was capped).
     */
    public SearchPage searchAll(String query, String from, String until, String order, int maxFetch) {
        String q = (query == null || query.isBlank()) ? "*" : query;
        String dir = (order == null || order.isBlank()) ? "desc" : order;
        int cap = maxFetch <= 0 ? LOGGLY_MAX_PAGE : maxFetch;

        List<LogEvent> all = new ArrayList<>();
        SearchPage page = this.fetchFirstPage(q, from, until, dir, cap);
        while (page != null) {
            if (page.events() != null) {
                all.addAll(page.events());
            }
            String next = page.next();
            if (isLastPage(next, all, cap)) {
                return boundedResult(all, cap, next);
            }
            page = this.fetchNextPage(next);
        }
        return new SearchPage(List.copyOf(all), null);
    }

    /** First page via {@code /events/iterate}, with the caller's raw (un-normalized) time bounds. */
    private SearchPage fetchFirstPage(String q, String from, String until, String dir, int cap) {
        String normalizedFrom = normalizeTime(from);
        String normalizedUntil = normalizeTime(until);
        return this.rest.get()
                .uri(b -> b.path("/events/iterate")
                        .queryParam("q", q)
                        .queryParam("from", normalizedFrom != null ? normalizedFrom : "-24h")
                        .queryParam("until", normalizedUntil != null ? normalizedUntil : "now")
                        .queryParam("order", dir)
                        .queryParam("size", Math.min(cap, LOGGLY_MAX_PAGE))
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(SearchPage.class);
    }

    /** True once there is no further cursor to follow, or the fetch cap has been reached. */
    private static boolean isLastPage(String next, List<LogEvent> all, int cap) {
        return next == null || next.isBlank() || all.size() >= cap;
    }

    /** Bounds the accumulated events to {@code cap} and reports {@code capped} iff more remained. */
    private static SearchPage boundedResult(List<LogEvent> all, int cap, String next) {
        boolean capped = next != null && !next.isBlank() && all.size() >= cap;
        List<LogEvent> bounded = all.size() > cap ? all.subList(0, cap) : all;
        return new SearchPage(List.copyOf(bounded), capped ? next : null);
    }

    /**
     * GET an opaque {@code next} cursor URL. The URL is fully formed (Loggly forbids changing its
     * parameters), so it is passed to {@code RestClient} verbatim as an absolute {@link URI}, which
     * overrides the configured base URL; the default {@code Authorization: bearer …} header still
     * applies.
     */
    private SearchPage fetchNextPage(String nextUrl) {
        return this.rest.get()
                .uri(URI.create(nextUrl))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(SearchPage.class);
    }

    /**
     * GET /apiv2/fields/ — the available field names with their top values/counts for a query
     * and time window. A discovery aid for building queries. Returned verbatim as a {@link JsonNode}
     * since the field set is dynamic.
     */
    public JsonNode listFields(String query, String from, String until, int facetSize) {
        String normalizedFrom = normalizeTime(from);
        String normalizedUntil = normalizeTime(until);
        return this.rest.get()
                .uri(b -> b.path("/fields/")
                        .queryParam("q", (query == null || query.isBlank()) ? "*" : query)
                        .queryParam("from", normalizedFrom != null ? normalizedFrom : "-24h")
                        .queryParam("until", normalizedUntil != null ? normalizedUntil : "now")
                        .queryParam("facet_size", facetSize <= 0 ? 10 : Math.min(facetSize, 500))
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(JsonNode.class);
    }

    /**
     * GET /apiv2/fields/{field}/ — the distinct values of one field with their counts (a facet).
     * The trailing slash is required by Loggly. Returned verbatim as a {@link JsonNode}.
     */
    public JsonNode fieldValues(String field, String query, String from, String until, int facetSize) {
        String normalizedFrom = normalizeTime(from);
        String normalizedUntil = normalizeTime(until);
        return this.rest.get()
                .uri(b -> b.path("/fields/{field}/")
                        .queryParam("q", (query == null || query.isBlank()) ? "*" : query)
                        .queryParam("from", normalizedFrom != null ? normalizedFrom : "-24h")
                        .queryParam("until", normalizedUntil != null ? normalizedUntil : "now")
                        .queryParam("facet_size", facetSize <= 0 ? 50 : Math.min(facetSize, 300))
                        .build(field))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raise)
                .body(JsonNode.class);
    }

    /**
     * Normalizes a caller-supplied time into something Loggly accepts. Loggly relative spans
     * carry a leading minus ({@code -24h}, {@code -7d}); a bare span ({@code 24h}) is widened to
     * that form. Bare dates ({@code yyyy-MM-dd}) become a UTC start-of-day instant; signed spans
     * and full ISO-8601 timestamps pass through unchanged.
     */
    static String normalizeTime(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String value = input.trim();
        if (value.matches("-?\\d+[smhdw]")) {
            value = value.startsWith("-") ? value : "-" + value;
        } else if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            value = value + "T00:00:00Z";
        }
        return value;
    }

    /**
     * Resolves a caller-supplied time to an absolute {@link Instant} relative to {@code now}, for
     * deciding whether a window is stable (immutable). Returns null if the value cannot be resolved
     * locally — callers should then treat the window as live, never reusing a snapshot.
     *
     * <p>{@code null}/blank/{@code now} resolve to {@code now}; relative spans ({@code -10m},
     * {@code 24h}) offset back from {@code now}; bare dates resolve to UTC start of day; ISO-8601
     * timestamps parse directly.
     */
    public static Instant resolveInstant(String input, Instant now) {
        if (input == null || input.isBlank() || input.trim().equalsIgnoreCase("now")) {
            return now;
        }
        String value = input.trim();
        Instant result;
        if (value.matches("-?\\d+[smhdw]")) {
            result = now.minus(parseSpan(value));
        } else if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            result = parseBareDate(value);
        } else {
            result = parseIsoInstant(value);
        }
        return result;
    }

    /** Spans always look backward from now. */
    private static Duration parseSpan(String value) {
        long n = Long.parseLong(value.replaceAll("[^0-9]", ""));
        return switch (value.charAt(value.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            case 'w' -> Duration.ofDays(n * 7);
            default -> Duration.ZERO;
        };
    }

    /** Null if calendar-invalid despite matching the shape (e.g. month 13) — caller treats as live. */
    private static Instant parseBareDate(String value) {
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeException ex) {
            return null;
        }
    }

    /** Null if unresolvable — caller treats the window as live (safe default). */
    private static Instant parseIsoInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        throw new LogglyApiException(response.getStatusCode().value(), body);
    }
}
