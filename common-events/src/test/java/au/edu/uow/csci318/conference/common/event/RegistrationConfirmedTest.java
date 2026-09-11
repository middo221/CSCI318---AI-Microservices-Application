package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationConfirmedTest {

    private static final RegistrationConfirmed CONFIRMED = new RegistrationConfirmed(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "reg-1",
            "att-1042",
            "Dana Okafor",
            "dana@example.com",
            "conf-1",
            List.of("ses-1", "ses-2"));

    @Test
    void publishesToTheRegistrationConfirmedTopic() {
        assertThat(CONFIRMED.eventType()).isEqualTo("registration-confirmed");
    }

    @Test
    void isKeyedByRegistrationId() {
        assertThat(CONFIRMED.partitionKey()).isEqualTo("reg-1");
    }

    /** Ticketing issues one ticket per confirmed session, so the session list has to travel. */
    @Test
    void carriesEverySessionTicketingMustIssueATicketFor() {
        assertThat(CONFIRMED.registrationId()).isEqualTo("reg-1");
        assertThat(CONFIRMED.attendeeId()).isEqualTo("att-1042");
        assertThat(CONFIRMED.conferenceEventId()).isEqualTo("conf-1");
        assertThat(CONFIRMED.sessionIds()).containsExactly("ses-1", "ses-2");
    }

    /**
     * Ticketing's {@code Holder} value object requires a non-blank name and email, and this event
     * is its only source for them — the same reason the session list travels.
     */
    @Test
    void carriesTheAttendeeNameAndEmailTicketingNeedsForAHolder() {
        assertThat(CONFIRMED.attendeeName()).isEqualTo("Dana Okafor");
        assertThat(CONFIRMED.attendeeEmail()).isEqualTo("dana@example.com");
    }

    /**
     * Deliberately unvalidated, unlike {@code sessionIds}. A message produced before these two
     * components existed must still deserialise: ticketing rejects the missing holder in its
     * application layer, where its consumer can absorb the failure and move on. Throwing here
     * instead would throw inside the binder's message converter, which redelivers forever and
     * blocks the partition for every registration queued behind it.
     */
    @Test
    void toleratesAMessageProducedBeforeTheHolderComponentsExisted() {
        assertThatNoException().isThrownBy(() -> new RegistrationConfirmed(
                "occ-legacy",
                Instant.parse("2026-09-03T02:15:30Z"),
                "reg-legacy",
                "att-1042",
                null,
                null,
                "conf-1",
                List.of("ses-1")));
    }

    @Test
    void copiesTheSessionListSoTheEventCannotBeMutatedAfterPublication() {
        List<String> mutable = new ArrayList<>(List.of("ses-1"));
        RegistrationConfirmed event = new RegistrationConfirmed(
                "occ-2", Instant.parse("2026-09-03T02:15:30Z"), "reg-2", "att-1",
                "Dana Okafor", "dana@example.com", "conf-1", mutable);

        mutable.add("ses-99");

        assertThat(event.sessionIds()).containsExactly("ses-1");
        assertThatThrownBy(() -> event.sessionIds().add("ses-100"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAConfirmationCoveringNoSessions() {
        assertThatThrownBy(() -> new RegistrationConfirmed(
                "occ-3", Instant.parse("2026-09-03T02:15:30Z"), "reg-3", "att-1",
                "Dana Okafor", "dana@example.com", "conf-1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
