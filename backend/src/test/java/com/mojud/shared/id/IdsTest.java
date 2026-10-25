package com.mojud.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void idsAreVersion7WithTheStandardVariant() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void theFirst48BitsAreTheCreationTime() {
        Instant now = Instant.parse("2026-10-25T10:15:30.123Z");

        UUID id = Ids.newId(Clock.fixed(now, ZoneOffset.UTC));

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
    }

    @Test
    void laterIdsSortAfterEarlierOnes() {
        UUID earlier = Ids.newId(Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC));
        UUID later = Ids.newId(Clock.fixed(Instant.parse("2026-10-25T10:00:00.001Z"), ZoneOffset.UTC));

        // PostgreSQL compares uuids as unsigned bytes, the same order as their text form.
        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void idsCreatedInTheSameMillisecondDiffer() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId(clock));
        }

        assertThat(ids).hasSize(10_000);
    }
}
