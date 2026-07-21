package com.sparrowlogic.logglymcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One page of results from the Loggly paginating event-retrieval API
 * ({@code /apiv2/events/iterate}).
 *
 * <p>Loggly returns the events for this page plus, when more pages exist, a {@code next} field
 * holding a fully-formed URL for the following page. The {@code next} URL is an opaque cursor:
 * pass it verbatim to {@code fetch_next_page} (its query parameters must not be altered). When
 * {@code next} is absent/null, this is the last page. The cursor expires after ~10 minutes of
 * inactivity.
 *
 * @param events the events on this page
 * @param next   opaque cursor URL for the next page, or null when no further pages exist
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchPage(
        List<LogEvent> events,
        String next) {
}
