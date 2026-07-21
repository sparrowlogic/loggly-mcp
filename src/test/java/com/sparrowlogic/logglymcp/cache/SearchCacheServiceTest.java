package com.sparrowlogic.logglymcp.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.sparrowlogic.logglymcp.client.LogglyClient;
import com.sparrowlogic.logglymcp.config.LogglyProperties;
import com.sparrowlogic.logglymcp.domain.LogEvent;
import com.sparrowlogic.logglymcp.domain.SearchPage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchCacheServiceTest {

	private LogglyClient client;
	private SearchCacheService cache;

	private static final LogglyProperties PROPERTIES =
			new LogglyProperties("acme", "token", 1000, 25, 200, 500, 5, 30, 2);

	@BeforeEach
	void setUp() {
		client = Mockito.mock(LogglyClient.class);
		cache = new SearchCacheService(client, PROPERTIES);
	}

	private static SearchPage pageOf(String eventId) {
		LogEvent event = new LogEvent(eventId, 1L, "msg", "raw", null, List.of(), List.of(), null);
		return new SearchPage(List.of(event), null);
	}

	@Test
	void liveWindow_alwaysFetchesFresh() {
		when(client.searchAll(anyString(), any(), any(), anyString(), anyInt()))
				.thenReturn(pageOf("e1"))
				.thenReturn(pageOf("e2"));

		SearchCacheService.Handle first = cache.search("*", "-10m", "now", "desc");
		SearchCacheService.Handle second = cache.search("*", "-10m", "now", "desc");

		assertThat(first.reused()).isFalse();
		assertThat(second.reused()).isFalse();
		assertThat(first.search().searchId()).isNotEqualTo(second.search().searchId());
		verify(client, times(2)).searchAll(anyString(), any(), any(), anyString(), anyInt());
	}

	@Test
	void stableWindow_reusesIdenticalSnapshot() {
		// Absolute (not relative) bounds: relative spans re-resolve against a fresh Instant.now()
		// on each call and would drift between the two search() calls, changing the stable key.
		Instant until = Instant.now().minus(Duration.ofDays(1));
		Instant from = until.minus(Duration.ofDays(2));
		when(client.searchAll(anyString(), any(), any(), anyString(), anyInt())).thenReturn(pageOf("e1"));

		SearchCacheService.Handle first =
				cache.search("json.level:ERROR", from.toString(), until.toString(), "desc");
		SearchCacheService.Handle second =
				cache.search("json.level:ERROR", from.toString(), until.toString(), "desc");

		assertThat(first.reused()).isFalse();
		assertThat(second.reused()).isTrue();
		assertThat(second.search().searchId()).isEqualTo(first.search().searchId());
		verify(client, times(1)).searchAll(anyString(), any(), any(), anyString(), anyInt());
	}

	@Test
	void stableWindow_differentQuery_doesNotReuse() {
		Instant until = Instant.now().minus(Duration.ofDays(1));
		Instant from = until.minus(Duration.ofDays(2));
		when(client.searchAll(anyString(), any(), any(), anyString(), anyInt()))
				.thenReturn(pageOf("e1"))
				.thenReturn(pageOf("e2"));

		cache.search("json.level:ERROR", from.toString(), until.toString(), "desc");
		SearchCacheService.Handle second =
				cache.search("json.level:WARN", from.toString(), until.toString(), "desc");

		assertThat(second.reused()).isFalse();
		verify(client, times(2)).searchAll(anyString(), any(), any(), anyString(), anyInt());
	}

	@Test
	void get_returnsCachedSnapshotById() {
		when(client.searchAll(anyString(), any(), any(), anyString(), anyInt())).thenReturn(pageOf("e1"));
		SearchCacheService.Handle handle = cache.search("*", "-10m", "now", "desc");

		CachedSearch fetched = cache.get(handle.search().searchId());

		assertThat(fetched.searchId()).isEqualTo(handle.search().searchId());
		assertThat(fetched.events()).extracting(LogEvent::id).containsExactly("e1");
	}

	@Test
	void get_unknownSearchId_throws() {
		assertThatThrownBy(() -> cache.get("does-not-exist"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("does-not-exist");
	}

	@Test
	void get_nullSearchId_throws() {
		assertThatThrownBy(() -> cache.get(null)).isInstanceOf(IllegalArgumentException.class);
	}
}
