package com.sparrowlogic.logglymcp.tools;

import com.sparrowlogic.logglymcp.cache.CachedSearch;
import com.sparrowlogic.logglymcp.cache.SearchCacheService;
import com.sparrowlogic.logglymcp.client.LogglyClient;
import com.sparrowlogic.logglymcp.config.LogglyProperties;
import com.sparrowlogic.logglymcp.domain.LogEvent;
import com.sparrowlogic.logglymcp.domain.SearchResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class LogglyToolsTest {

    private static final LogglyProperties PROPERTIES =
            new LogglyProperties("acme", "token", 1000, 25, 200, 500, 5, 30, 2);

    private SearchCacheService cache;
    private LogglyClient client;
    private LogglyTools tools;

    @BeforeEach
    void setUp() {
        cache = Mockito.mock(SearchCacheService.class);
        client = Mockito.mock(LogglyClient.class);
        tools = new LogglyTools(cache, client, PROPERTIES);
    }

    private static LogEvent event(String id) {
        return new LogEvent(id, 1L, "msg-" + id, "raw-" + id, null, List.of("syslog"), List.of("prod"), null);
    }

    private static CachedSearch snapshotOf(String searchId, List<LogEvent> events) {
        return new CachedSearch(searchId, "*", "-24h", "now", "desc", events, false, false, 0L, Long.MAX_VALUE);
    }

    @Test
    void getEvent_returnsMatchingEventFromSnapshot() {
        CachedSearch snapshot = snapshotOf("s1", List.of(event("e1"), event("e2")));
        when(cache.get("s1")).thenReturn(snapshot);

        LogEvent found = tools.getEvent("s1", "e2");

        assertThat(found.id()).isEqualTo("e2");
        assertThat(found.logmsg()).isEqualTo("msg-e2");
    }

    @Test
    void getEvent_unknownEventId_throwsWithSearchIdAndEventIdInMessage() {
        CachedSearch snapshot = snapshotOf("s1", List.of(event("e1")));
        when(cache.get("s1")).thenReturn(snapshot);

        assertThatThrownBy(() -> tools.getEvent("s1", "does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist")
                .hasMessageContaining("s1");
    }

    @Test
    void fetchPage_slicesEventsAndReportsHasMore() {
        List<LogEvent> events = List.of(event("e1"), event("e2"), event("e3"));
        when(cache.get("s1")).thenReturn(snapshotOf("s1", events));

        SearchResult page0 = tools.fetchPage("s1", 0, 2);

        assertThat(page0.events()).extracting(c -> c.id()).containsExactly("e1", "e2");
        assertThat(page0.hasMore()).isTrue();
        assertThat(page0.nextPage()).isEqualTo(1);
        assertThat(page0.totalFetched()).isEqualTo(3);
    }

    @Test
    void fetchPage_lastPage_hasNoMoreAndNullNextPage() {
        List<LogEvent> events = List.of(event("e1"), event("e2"), event("e3"));
        when(cache.get("s1")).thenReturn(snapshotOf("s1", events));

        SearchResult page1 = tools.fetchPage("s1", 1, 2);

        assertThat(page1.events()).extracting(c -> c.id()).containsExactly("e3");
        assertThat(page1.hasMore()).isFalse();
        assertThat(page1.nextPage()).isNull();
    }

    @Test
    void fetchPage_pageIndexBeyondTotal_returnsEmptyEventsNotAnError() {
        List<LogEvent> events = List.of(event("e1"));
        when(cache.get("s1")).thenReturn(snapshotOf("s1", events));

        SearchResult page = tools.fetchPage("s1", 5, 2);

        assertThat(page.events()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void fetchPage_nullPage_defaultsToZero() {
        List<LogEvent> events = List.of(event("e1"), event("e2"));
        when(cache.get("s1")).thenReturn(snapshotOf("s1", events));

        SearchResult page = tools.fetchPage("s1", null, 10);

        assertThat(page.page()).isZero();
        assertThat(page.events()).extracting(c -> c.id()).containsExactly("e1", "e2");
    }

    @Test
    void fetchPage_pageSize_defaultsWhenNullOrNonPositive() {
        List<LogEvent> events = List.of(event("e1"));
        when(cache.get("s1")).thenReturn(snapshotOf("s1", events));

        assertThat(tools.fetchPage("s1", 0, null).pageSize()).isEqualTo(PROPERTIES.defaultPageSize());
        assertThat(tools.fetchPage("s1", 0, 0).pageSize()).isEqualTo(PROPERTIES.defaultPageSize());
        assertThat(tools.fetchPage("s1", 0, -5).pageSize()).isEqualTo(PROPERTIES.defaultPageSize());
    }

    @Test
    void fetchPage_pageSize_clampedToMax() {
        List<LogEvent> events = List.of(event("e1"));
        when(cache.get("s1")).thenReturn(snapshotOf("s1", events));

        SearchResult page = tools.fetchPage("s1", 0, 100_000);

        assertThat(page.pageSize()).isEqualTo(PROPERTIES.maxPageSize());
    }

    @Test
    void searchLogs_delegatesToCacheAndReturnsFirstPage() {
        List<LogEvent> events = List.of(event("e1"), event("e2"));
        CachedSearch snapshot = snapshotOf("s1", events);
        when(cache.search("json.level:ERROR", "-24h", "now", "desc"))
                .thenReturn(new SearchCacheService.Handle(snapshot, false));

        SearchResult result = tools.searchLogs("json.level:ERROR", "-24h", "now", "desc", null);

        assertThat(result.searchId()).isEqualTo("s1");
        assertThat(result.page()).isZero();
        assertThat(result.cached()).isFalse();
        assertThat(result.events()).extracting(c -> c.id()).containsExactly("e1", "e2");
    }
}
