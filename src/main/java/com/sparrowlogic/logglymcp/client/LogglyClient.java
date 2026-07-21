package com.sparrowlogic.logglymcp.client;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import com.sparrowlogic.logglymcp.domain.LogEvent;
import com.sparrowlogic.logglymcp.domain.SearchPage;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
		String normalizedFrom = normalizeTime(from);
		String normalizedUntil = normalizeTime(until);
		String dir = (order == null || order.isBlank()) ? "desc" : order;
		int cap = maxFetch <= 0 ? LOGGLY_MAX_PAGE : maxFetch;

		List<LogEvent> all = new ArrayList<>();
		// First page via /events/iterate; subsequent pages by following the returned next URL.
		SearchPage page = rest.get()
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

		while (page != null) {
			if (page.events() != null) {
				all.addAll(page.events());
			}
			String next = page.next();
			if (next == null || next.isBlank() || all.size() >= cap) {
				// Capped iff more events remain (a next cursor) but we have hit the cap.
				boolean capped = next != null && !next.isBlank() && all.size() >= cap;
				List<LogEvent> bounded = all.size() > cap ? all.subList(0, cap) : all;
				return new SearchPage(List.copyOf(bounded), capped ? next : null);
			}
			page = fetchNextPage(next);
		}
		return new SearchPage(List.copyOf(all), null);
	}

	/**
	 * GET an opaque {@code next} cursor URL. The URL is fully formed (Loggly forbids changing its
	 * parameters), so it is passed to {@code RestClient} verbatim as an absolute {@link URI}, which
	 * overrides the configured base URL; the default {@code Authorization: bearer …} header still
	 * applies.
	 */
	private SearchPage fetchNextPage(String nextUrl) {
		return rest.get()
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
		return rest.get()
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
		return rest.get()
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
			return value.startsWith("-") ? value : "-" + value; // relative span -> Loggly form
		}
		if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
			return value + "T00:00:00Z"; // bare date -> UTC start of day
		}
		return value; // assume already valid (ISO-8601 timestamp, or "now")
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
		if (value.matches("-?\\d+[smhdw]")) {
			long n = Long.parseLong(value.replaceAll("[^0-9]", ""));
			Duration d = switch (value.charAt(value.length() - 1)) {
				case 's' -> Duration.ofSeconds(n);
				case 'm' -> Duration.ofMinutes(n);
				case 'h' -> Duration.ofHours(n);
				case 'd' -> Duration.ofDays(n);
				case 'w' -> Duration.ofDays(n * 7);
				default -> Duration.ZERO;
			};
			return now.minus(d); // spans always look backward from now
		}
		if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
			try {
				return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
			}
			catch (RuntimeException ex) {
				return null; // calendar-invalid despite matching the shape (e.g. month 13) -> live
			}
		}
		try {
			return Instant.parse(value);
		}
		catch (RuntimeException ex) {
			return null; // unresolvable -> caller treats window as live (safe default)
		}
	}

	private void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
		String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
		throw new LogglyApiException(response.getStatusCode().value(), body);
	}
}
