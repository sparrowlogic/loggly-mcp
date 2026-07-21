package com.sparrowlogic.logglymcp.client;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogglyClientTest {

    private static final Instant NOW = Instant.parse("2026-07-21T20:00:00Z");

    @Test
    void normalizeTime_nullAndBlank_resolveToNull() {
        assertThat(LogglyClient.normalizeTime(null)).isNull();
        assertThat(LogglyClient.normalizeTime("")).isNull();
        assertThat(LogglyClient.normalizeTime("   ")).isNull();
    }

    @Test
    void normalizeTime_bareRelativeSpan_getsLeadingMinus() {
        assertThat(LogglyClient.normalizeTime("24h")).isEqualTo("-24h");
        assertThat(LogglyClient.normalizeTime("7d")).isEqualTo("-7d");
        assertThat(LogglyClient.normalizeTime("10m")).isEqualTo("-10m");
        assertThat(LogglyClient.normalizeTime("30s")).isEqualTo("-30s");
        assertThat(LogglyClient.normalizeTime("2w")).isEqualTo("-2w");
    }

    @Test
    void normalizeTime_signedRelativeSpan_passesThroughUnchanged() {
        assertThat(LogglyClient.normalizeTime("-24h")).isEqualTo("-24h");
    }

    @Test
    void normalizeTime_bareDate_becomesUtcStartOfDay() {
        assertThat(LogglyClient.normalizeTime("2026-06-01")).isEqualTo("2026-06-01T00:00:00Z");
    }

    @Test
    void normalizeTime_isoTimestampAndNow_passThroughUnchanged() {
        assertThat(LogglyClient.normalizeTime("2026-06-01T12:30:00Z")).isEqualTo("2026-06-01T12:30:00Z");
        assertThat(LogglyClient.normalizeTime("now")).isEqualTo("now");
    }

    @Test
    void normalizeTime_trimsWhitespace() {
        assertThat(LogglyClient.normalizeTime("  24h  ")).isEqualTo("-24h");
    }

    @Test
    void resolveInstant_nullBlankAndNow_resolveToNow() {
        assertThat(LogglyClient.resolveInstant(null, NOW)).isEqualTo(NOW);
        assertThat(LogglyClient.resolveInstant("", NOW)).isEqualTo(NOW);
        assertThat(LogglyClient.resolveInstant("now", NOW)).isEqualTo(NOW);
        assertThat(LogglyClient.resolveInstant("NOW", NOW)).isEqualTo(NOW);
    }

    @Test
    void resolveInstant_relativeSpans_offsetBackFromNow() {
        assertThat(LogglyClient.resolveInstant("-10m", NOW)).isEqualTo(NOW.minus(Duration.ofMinutes(10)));
        assertThat(LogglyClient.resolveInstant("10m", NOW)).isEqualTo(NOW.minus(Duration.ofMinutes(10)));
        assertThat(LogglyClient.resolveInstant("-24h", NOW)).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(LogglyClient.resolveInstant("-7d", NOW)).isEqualTo(NOW.minus(Duration.ofDays(7)));
        assertThat(LogglyClient.resolveInstant("-2w", NOW)).isEqualTo(NOW.minus(Duration.ofDays(14)));
        assertThat(LogglyClient.resolveInstant("-30s", NOW)).isEqualTo(NOW.minus(Duration.ofSeconds(30)));
    }

    @Test
    void resolveInstant_bareDate_resolvesToUtcStartOfDay() {
        assertThat(LogglyClient.resolveInstant("2026-06-01", NOW))
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void resolveInstant_isoTimestamp_parsesDirectly() {
        assertThat(LogglyClient.resolveInstant("2026-06-01T12:30:00Z", NOW))
                .isEqualTo(Instant.parse("2026-06-01T12:30:00Z"));
    }

    @Test
    void resolveInstant_unresolvableInput_returnsNull() {
        assertThat(LogglyClient.resolveInstant("not-a-time", NOW)).isNull();
        assertThat(LogglyClient.resolveInstant("2026-13-99", NOW)).isNull();
    }
}
