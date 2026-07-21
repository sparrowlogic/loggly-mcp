package com.sparrowlogic.logglymcp.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompactEventTest {

	private static LogEvent event(String logmsg, String raw) {
		return new LogEvent("id-1", 1_700_000_000_000L, logmsg, raw, null,
				List.of("syslog"), List.of("prod"), null);
	}

	@Test
	void usesLogmsgWhenPresent() {
		CompactEvent c = CompactEvent.from(event("parsed message", "raw line"), 500);
		assertThat(c.message()).isEqualTo("parsed message");
	}

	@Test
	void fallsBackToRawWhenLogmsgIsNull() {
		CompactEvent c = CompactEvent.from(event(null, "raw line"), 500);
		assertThat(c.message()).isEqualTo("raw line");
	}

	@Test
	void fallsBackToRawWhenLogmsgIsBlank() {
		CompactEvent c = CompactEvent.from(event("   ", "raw line"), 500);
		assertThat(c.message()).isEqualTo("raw line");
	}

	@Test
	void messageIsNullWhenBothLogmsgAndRawAreNull() {
		CompactEvent c = CompactEvent.from(event(null, null), 500);
		assertThat(c.message()).isNull();
	}

	@Test
	void doesNotTruncateWhenUnderLimit() {
		CompactEvent c = CompactEvent.from(event("short", null), 500);
		assertThat(c.message()).isEqualTo("short");
	}

	@Test
	void doesNotTruncateWhenExactlyAtLimit() {
		String text = "x".repeat(10);
		CompactEvent c = CompactEvent.from(event(text, null), 10);
		assertThat(c.message()).isEqualTo(text);
	}

	@Test
	void truncatesAndAppendsMarkerWhenOverLimit() {
		String text = "x".repeat(20);
		CompactEvent c = CompactEvent.from(event(text, null), 10);
		assertThat(c.message())
				.isEqualTo("x".repeat(10) + "… [truncated — use get_event for full text]");
	}

	@Test
	void truncationDoesNotSplitASurrogatePair() {
		// U+1F600 (😀) is a surrogate pair in UTF-16: cutting at index 6 would otherwise land
		// between the high and low surrogate, leaving a lone/unpaired surrogate in the output.
		String text = "hello 😀 world";
		CompactEvent c = CompactEvent.from(event(text, null), 6);

		String message = c.message();
		assertThat(message).startsWith("hello ");
		for (int i = 0; i < message.length() - 1; i++) {
			if (Character.isHighSurrogate(message.charAt(i))) {
				assertThat(Character.isLowSurrogate(message.charAt(i + 1)))
						.as("high surrogate at %d must be followed by its low surrogate", i)
						.isTrue();
			}
		}
		assertThat(Character.isSurrogate(message.charAt(message.length() - 1)))
				.as("message must not end mid-surrogate-pair")
				.isFalse();
	}

	@Test
	void timeIsFormattedAsIso8601Utc() {
		CompactEvent c = CompactEvent.from(event("m", null), 500);
		assertThat(c.time()).isEqualTo("2023-11-14T22:13:20Z");
	}

	@Test
	void preservesIdTagsAndLogtypes() {
		LogEvent e = new LogEvent("evt-42", 1L, "m", "r", null,
				List.of("syslog", "json"), List.of("prod", "web"), null);
		CompactEvent c = CompactEvent.from(e, 500);
		assertThat(c.id()).isEqualTo("evt-42");
		assertThat(c.logtypes()).containsExactly("syslog", "json");
		assertThat(c.tags()).containsExactly("prod", "web");
	}
}
