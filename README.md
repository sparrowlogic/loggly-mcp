# loggly-mcp

A read-only [Model Context Protocol](https://modelcontextprotocol.io) server that exposes
[Loggly](https://www.loggly.com) log search to MCP clients (Claude Code, etc.). Built with
**Spring AI 2.0.0** (`spring-ai-starter-mcp-server`) on Spring Boot 4, communicating over
**stdio**.

## Tools

Log aggregation returns large result sets, so search is **fetch-once-then-paginate**: a search
fetches its matching events once, caches them server-side, and hands back small *compact* pages.
Pagination and event drill-down read the cache — only `search_logs` ever calls Loggly's events API.

| Tool | Purpose |
| --- | --- |
| `search_logs` | Run a search, cache the result set under a returned `searchId`, and return page 0 as compact events (`id`, `time`, `tags`, `logtypes`, truncated `message`). |
| `fetch_page` | Return another page (`searchId`, `page`) from the cached snapshot — no re-query. |
| `get_event` | Return one event's **complete** payload (full message, raw line, parsed tree) from the cache, by `searchId` + event `id`. |
| `list_fields` | List available log fields with their top values/counts — a discovery aid for building queries. |
| `field_values` | The distinct values of one field with occurrence counts (a facet), e.g. top `syslog.host`. |

`query` uses [Loggly search syntax](https://documentation.solarwinds.com/en/success_center/loggly/content/admin/search-query-language.htm)
(e.g. `json.level:ERROR`, `"connection refused"`, `tag:prod AND syslog.severity:err`); omit or
pass `*` to match everything. Time bounds accept Loggly relative spans with a leading minus
(`-24h`, `-7d`), bare dates (`2026-06-01`), full ISO-8601 timestamps, or `now`. Defaults are
`from=-24h`, `until=now`.

Each `search_logs`/`fetch_page` result carries pagination/snapshot metadata: `hasMore`,
`nextPage`, `totalFetched`, `capped` (Loggly held more events than the fetch cap — narrow the
query), `cached` (served from a reused stable snapshot), and `windowStable`.

### Caching

The result set is fetched once (eagerly, up to `loggly.max-fetch` events ≈ one Loggly max page)
and cached in the long-lived stdio process. **Stable** windows — whose resolved `until` is older
than `loggly.stable-window-minutes` (5 min), so the data is immutable — are reused when an
identical query is re-issued, and held longer (`stable-ttl-minutes`). **Live** windows (`until ≈
now`) return changing data, so they are never reused: each call mints a fresh `searchId`, kept
only briefly (`live-ttl-minutes`). Within a single search the paged snapshot is always consistent.

## Architecture

- `client/LogglyClient` — `RestClient`-based port centred on Loggly's
  [paginating event-retrieval API](https://documentation.solarwinds.com/en/success_center/loggly/content/admin/paginating-event-retrieval-api.htm)
  (`/apiv2/events/iterate`). That endpoint returns `{ events, next }`, where `next` is a
  fully-formed URL for the following page. `searchAll` eagerly follows those `next` cursors up to
  `loggly.max-fetch` (so the snapshot is fetched in one shot rather than chasing Loggly's
  ~10-min-expiry cursors mid-pagination), GETting each URL verbatim (Loggly forbids changing its
  parameters) as an absolute URI so it overrides the base URL while keeping the `bearer` header.
  `resolveInstant` turns relative/ISO times into absolute instants for the stable-window decision.
  Discovery uses the `/apiv2/fields/` facet endpoints (note the required trailing slash).
- `cache/SearchCacheService` — process-local store of fetched snapshots (`CachedSearch`). Holds
  the **full** events; pages project them to compact form at serve time. Stable windows are keyed
  on resolved instants and reused; live windows always fetch fresh. TTL-evicted.
- `tools/LogglyTools` — `@McpTool`-annotated methods, auto-registered by the MCP server's
  annotation scanner (`spring.ai.mcp.server.annotation-scanner.enabled` defaults to true).
  `search_logs`/`fetch_page` return compact `SearchResult` pages; `get_event` returns a full
  `LogEvent` straight from the cache (no second Loggly call).
- `config/` — `LogglyProperties` (`loggly.*`, with `baseUrl()` computed from the subdomain) and
  the configured `RestClient` bean (base URL + `Authorization: bearer …`).
- JSON binding uses **Jackson 3** (`tools.jackson`), the Spring Boot 4 default that the MCP SDK
  pulls in. `LogEvent`/`SearchPage` bind leniently (`@JsonIgnoreProperties(ignoreUnknown=true)`)
  and keep each event's parsed tree as a raw `JsonNode` so no source-specific fields are dropped.

> Compact projection is what keeps a page under the response token ceiling — the bulk lives in
> each event's parsed tree, which is omitted from pages and available only via `get_event`. The
> tool flow has been exercised against the live API; lenient binding remains so unexpected fields
> don't break deserialization.

## Configuration

Set via `loggly.*` in `application.yaml` or environment variables:

| Property | Env | Default |
| --- | --- | --- |
| `loggly.subdomain` | `LOGGLY_SUBDOMAIN` | `acme` (placeholder) — selects host `https://<subdomain>.loggly.com` |
| `loggly.api-token` | `LOGGLY_API_TOKEN` | _(required)_ — a Loggly **API token** (Source Setup → API Tokens), not the ingestion token |
| `loggly.max-fetch` | | `1000` — events pulled into one cached snapshot (≈ one Loggly max page) |
| `loggly.default-page-size` / `loggly.max-page-size` | | `25` / `200` — events per MCP page (kept modest to stay under the response token ceiling) |
| `loggly.message-max-chars` | | `500` — compact-event message truncation |
| `loggly.stable-window-minutes` | | `5` — windows ending older than this are immutable (reusable) |
| `loggly.stable-ttl-minutes` / `loggly.live-ttl-minutes` | | `30` / `2` — snapshot cache TTLs |

> Supply the token via `LOGGLY_API_TOKEN` rather than committing it. The base URL is derived as
> `https://<subdomain>.loggly.com/apiv2`.

## Build & run

```sh
# Build a self-executing fat jar (target/loggly-mcp-exec.jar)
make jar

# Install to ~/bin/mcp/ and register with Claude Code (user scope, name "loggly")
make ship
```

`make ship` runs `jar → install → register`. `register` runs
`claude mcp add --scope user loggly --env LOGGLY_API_TOKEN=… --env LOGGLY_SUBDOMAIN=… -- ~/bin/mcp/loggly-mcp.jar`.
The installed jar is **self-executing** (a shell prefix is prepended to the fat jar), so it can be
invoked directly.

Equivalent `.claude.json` / `claude_desktop_config.json` entry:

```json
{
  "mcpServers": {
    "loggly": {
      "type": "stdio",
      "command": "/Users/<you>/bin/mcp/loggly-mcp.jar",
      "args": [],
      "env": {
        "LOGGLY_API_TOKEN": "<your-api-token>",
        "LOGGLY_SUBDOMAIN": "<your-subdomain>"
      }
    }
  }
}
```

Plain `java -jar target/loggly-mcp.jar` also works.

## stdio notes

stdout is the JSON-RPC channel, so the app runs with `web-application-type=none`,
`banner-mode=off`, and logging suppressed to `ERROR` (stderr is reserved for diagnostics, never
stdout). Re-enable logs at runtime with e.g. `LOGGING_LEVEL_ROOT=INFO`.
