package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAddedTest {

    private static final SessionAdded ADDED = new SessionAdded(
            "occ-1",
            Instant.parse("2026-08-22T10:15:30Z"),
            "ses-1",
            "conf-1",
            "Observability in practice",
            "ML Infrastructure",
            LocalDateTime.parse("2026-09-14T09:00"),
            LocalDateTime.parse("2026-09-14T10:30"),
            "spk-1",
            new BigDecimal("49.50"),
            "AUD");

    @Test
    void publishesToTheSessionAddedTopic() {
        assertThat(ADDED.eventType()).isEqualTo("session-added");
    }

    @Test
    void isKeyedBySessionIdSoTheAnalyticsJoinIsCoPartitioned() {
        assertThat(ADDED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesTheSessionDetailARegistrationReplicaNeeds() {
        assertThat(ADDED.conferenceEventId()).isEqualTo("conf-1");
        assertThat(ADDED.title()).isEqualTo("Observability in practice");
        assertThat(ADDED.track()).isEqualTo("ML Infrastructure");
        assertThat(ADDED.startsAt()).isEqualTo(LocalDateTime.parse("2026-09-14T09:00"));
        assertThat(ADDED.endsAt()).isEqualTo(LocalDateTime.parse("2026-09-14T10:30"));
        assertThat(ADDED.speakerId()).isEqualTo("spk-1");
        assertThat(ADDED.priceAmount()).isEqualByComparingTo("49.50");
        assertThat(ADDED.priceCurrency()).isEqualTo("AUD");
    }
}
