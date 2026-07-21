package com.sparrowlogic.logglymcp.domain;

import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@link LogEvent}/{@link SearchPage} against the shapes Loggly's
 * {@code /apiv2/events/iterate} endpoint actually returns, and checks how outgoing JSON survives
 * malformed/unusual text that real log messages can carry (they are free-text from arbitrary
 * upstream sources, not controlled input).
 */
class LogEventJsonBindingTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void deserializesLogglyShapedEvent_andKeepsParsedTreeVerbatim() {
		String json = """
				{
				  "id": "abc-123",
				  "timestamp": 1700000000000,
				  "logmsg": "hello world",
				  "raw": "raw hello world",
				  "unparsed": null,
				  "logtypes": ["syslog", "json"],
				  "tags": ["prod"],
				  "event": {"json": {"level": "ERROR", "nested": {"a": [1, 2, 3]}}}
				}
				""";

		LogEvent event = mapper.readValue(json, LogEvent.class);

		assertThat(event.id()).isEqualTo("abc-123");
		assertThat(event.timestamp()).isEqualTo(1700000000000L);
		assertThat(event.logmsg()).isEqualTo("hello world");
		assertThat(event.raw()).isEqualTo("raw hello world");
		assertThat(event.unparsed()).isNull();
		assertThat(event.logtypes()).containsExactly("syslog", "json");
		assertThat(event.tags()).containsExactly("prod");
		assertThat(event.event().path("json").path("level").asString()).isEqualTo("ERROR");
		assertThat(event.event().path("json").path("nested").path("a").get(1).asInt()).isEqualTo(2);
	}

	@Test
	void ignoresUnknownFields_bothOnLogEventAndSearchPage() {
		String json = """
				{
				  "events": [
				    {"id": "e1", "timestamp": 1, "logmsg": "m", "unexpectedField": "ignored"}
				  ],
				  "next": null,
				  "totalEvents": 999,
				  "someOtherLogglyField": {"whatever": true}
				}
				""";

		SearchPage page = mapper.readValue(json, SearchPage.class);

		assertThat(page.events()).hasSize(1);
		assertThat(page.events().get(0).id()).isEqualTo("e1");
		assertThat(page.next()).isNull();
	}

	@Test
	void nextCursor_isPreservedWhenPresent() {
		String json = """
				{"events": [], "next": "https://acme.loggly.com/apiv2/events/iterate?next=abc"}
				""";

		SearchPage page = mapper.readValue(json, SearchPage.class);

		assertThat(page.events()).isEmpty();
		assertThat(page.next()).isEqualTo("https://acme.loggly.com/apiv2/events/iterate?next=abc");
	}

	@Test
	void missingOptionalFields_bindToNullRatherThanFailing() {
		String json = """
				{"id": "e1", "timestamp": 5}
				""";

		LogEvent event = mapper.readValue(json, LogEvent.class);

		assertThat(event.id()).isEqualTo("e1");
		assertThat(event.timestamp()).isEqualTo(5L);
		assertThat(event.logmsg()).isNull();
		assertThat(event.raw()).isNull();
		assertThat(event.logtypes()).isNull();
		assertThat(event.tags()).isNull();
		assertThat(event.event()).isNull();
	}

	@Test
	void unicodeMessageText_roundTripsThroughSerialization() {
		String withEmoji = "日本語 😀 café";
		LogEvent event = new LogEvent("id", 1L, withEmoji, withEmoji, null, null, null, null);

		String json = mapper.writeValueAsString(event);
		LogEvent roundTripped = mapper.readValue(json, LogEvent.class);

		assertThat(roundTripped.logmsg()).isEqualTo(withEmoji);
	}

	@Test
	void loneSurrogateInMessageText_serializesToValidJsonInsteadOfCorruptingOutput() {
		// A high surrogate with no matching low surrogate: can occur if upstream text was cut
		// mid-codepoint (e.g. a naive byte-length truncation elsewhere in a log pipeline).
		// This must never corrupt the stdio JSON-RPC stream — it must come out as *some* valid
		// JSON, even if the represented text is technically not well-formed Unicode.
		String withLoneSurrogate = "prefix \ud83d suffix";

		Map<String, Object> payload = Map.of("message", withLoneSurrogate);
		byte[] bytes = mapper.writeValueAsBytes(payload);

		// Must decode as UTF-8 without throwing/replacement mangling, and must be one JSON
		// object with no embedded raw newline (which would break line-based JSON-RPC framing).
		String asUtf8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
		assertThat(asUtf8).doesNotContain("\n").doesNotContain("\r");

		// And it must parse back as valid JSON.
		Map<?, ?> reparsed = mapper.readValue(bytes, Map.class);
		assertThat(reparsed.containsKey("message")).isTrue();
	}
}
