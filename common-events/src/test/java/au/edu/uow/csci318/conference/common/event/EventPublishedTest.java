package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EventPublishedTest {

    private static final EventPublished PUBLISHED = new EventPublished(
            "evt-occurrence-1",
            Instant.parse("2026-08-22T10:15:30Z"),
            "conf-1",
            "SoftEng Summit",
            LocalDate.of(2026, 9, 14),
            LocalDate.of(2026, 9, 16));

    @Test
    void publishesToTheEventPublishedTopic() {
        assertThat(PUBLISHED.eventType()).isEqualTo("event-published");
    }

    @Test
    void isKeyedByTheConferenceEventIdNotTheOccurrenceId() {
        assertThat(PUBLISHED.partitionKey()).isEqualTo("conf-1");
    }

    @Test
    void carriesTheProgrammeSummaryAConsumerNeeds() {
        assertThat(PUBLISHED.name()).isEqualTo("SoftEng Summit");
        assertThat(PUBLISHED.startDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(PUBLISHED.endDate()).isEqualTo(LocalDate.of(2026, 9, 16));
    }

    @Test
    void isComparedByValue() {
        EventPublished same = new EventPublished(
                "evt-occurrence-1",
                Instant.parse("2026-08-22T10:15:30Z"),
                "conf-1",
                "SoftEng Summit",
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 16));

        assertThat(PUBLISHED).isEqualTo(same).hasSameHashCodeAs(same);
    }
}
