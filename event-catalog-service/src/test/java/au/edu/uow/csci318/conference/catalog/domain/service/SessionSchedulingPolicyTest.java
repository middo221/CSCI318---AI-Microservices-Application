package au.edu.uow.csci318.conference.catalog.domain.service;

import au.edu.uow.csci318.conference.catalog.domain.model.DateRange;
import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.model.Price;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.catalog.domain.model.Track;
import au.edu.uow.csci318.conference.catalog.domain.model.Venue;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionSchedulingPolicyTest {

    private static final Track TRACK = new Track("ML Infrastructure");

    private static TimeSlot slot(String start, String end) {
        return new TimeSlot(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }

    /** Builds a single detached session by going through an aggregate, as production code does. */
    private static Session sessionFor(String speakerId, TimeSlot slot) {
        Event event = Event.createDraft("evt-1", "SoftEng Summit", null,
                new Venue("Innovation Campus", "Squires Way", "North Wollongong"),
                new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16)));
        return event.addSession("ses-" + speakerId + slot.start(), "Talk", TRACK, slot,
                speakerId, SeatCapacity.of(10), Price.aud("0"));
    }

    @Test
    void acceptsASpeakerWithNoOtherSessions() {
        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T09:00", "2026-09-14T10:00"), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsASpeakerWhoseOtherSessionsDoNotOverlap() {
        List<Session> existing = List.of(sessionFor("spk-1", slot("2026-09-14T11:00", "2026-09-14T12:00")));

        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T09:00", "2026-09-14T10:00"), existing))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsBackToBackSessionsForTheSameSpeaker() {
        List<Session> existing = List.of(sessionFor("spk-1", slot("2026-09-14T09:00", "2026-09-14T10:00")));

        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T10:00", "2026-09-14T11:00"), existing))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnOverlapForTheSameSpeaker() {
        List<Session> existing = List.of(sessionFor("spk-1", slot("2026-09-14T09:00", "2026-09-14T10:30")));

        assertThatThrownBy(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T10:00", "2026-09-14T11:00"), existing))
                .isInstanceOf(SessionSchedulingPolicy.SpeakerDoubleBookedException.class)
                .hasMessageContaining("spk-1");
    }

    @Test
    void ignoresOverlapsBelongingToADifferentSpeaker() {
        List<Session> existing = List.of(sessionFor("spk-2", slot("2026-09-14T09:00", "2026-09-14T10:30")));

        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T10:00", "2026-09-14T11:00"), existing))
                .doesNotThrowAnyException();
    }

    @Test
    void toleratesNullAndEmptyInputs() {
        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T09:00", "2026-09-14T10:00"), null))
                .doesNotThrowAnyException();
    }
}
