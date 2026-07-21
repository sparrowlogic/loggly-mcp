package com.sparrowlogic.logglymcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the Loggly event-retrieval API client, bound from the {@code loggly.*}
 * properties.
 *
 * <p>Unlike a single-secret API, Loggly needs <em>two</em> pieces of identity: the account
 * {@code subdomain} (which selects the host {@code https://<subdomain>.loggly.com}) and an API
 * {@code token} (sent as {@code Authorization: bearer <token>}).
 *
 * @param subdomain            account subdomain — selects the host {@code https://<subdomain>.loggly.com}
 * @param apiToken             API token sent as {@code Authorization: bearer <apiToken>}
 * @param maxFetch             hard cap on the number of events pulled into one cached snapshot
 *                             (log aggregation can return enormous result sets; 1000 matches one
 *                             Loggly max page, so the common search is a single API call)
 * @param defaultPageSize      events returned per MCP page when a tool does not specify a size
 * @param maxPageSize          upper bound on the MCP page size a tool may request
 * @param messageMaxChars      compact-event message is truncated to this many characters
 * @param stableWindowMinutes  a window whose end is older than this many minutes is treated as
 *                             immutable ("ingestion has settled"), so its snapshot is reusable
 * @param stableTtlMinutes     cache TTL for stable (immutable) snapshots
 * @param liveTtlMinutes       cache TTL for live snapshots (end ≈ now), kept short
 */
@ConfigurationProperties(prefix = "loggly")
public record LogglyProperties(
        String subdomain,
        String apiToken,
        @DefaultValue("1000") int maxFetch,
        @DefaultValue("25") int defaultPageSize,
        @DefaultValue("200") int maxPageSize,
        @DefaultValue("500") int messageMaxChars,
        @DefaultValue("5") int stableWindowMinutes,
        @DefaultValue("30") int stableTtlMinutes,
        @DefaultValue("2") int liveTtlMinutes) {

    /** The API base URL for this account, e.g. {@code https://acme.loggly.com/apiv2}. */
    public String baseUrl() {
        return "https://" + this.subdomain + ".loggly.com/apiv2";
    }
}
