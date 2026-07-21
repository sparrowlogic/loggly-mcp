package com.sparrowlogic.logglymcp.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A single Loggly log event as returned by {@code /apiv2/events/iterate}.
 *
 * @param id        event id (UUID)
 * @param timestamp event time in UTC epoch milliseconds
 * @param logmsg    the parsed log message
 * @param raw       the raw, unparsed log line
 * @param unparsed  any portion Loggly could not parse (often null)
 * @param logtypes  Loggly-derived log type tags (e.g. {@code ["syslog"]})
 * @param tags      source tags attached at ingest
 * @param event     the structured, parsed event tree, kept verbatim as JSON so no
 *                  source-specific fields are dropped
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogEvent(
        String id,
        long timestamp,
        String logmsg,
        String raw,
        String unparsed,
        List<String> logtypes,
        List<String> tags,
        JsonNode event) {
}
