package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the registration replica is maintained entirely from catalog events, with no call back
 * into {@code event-catalog-service}.
 */
@SpringBootTest
@EnableTestBinder
class SessionReplicationTest {

    @Autowired
    private InputDestination inbound;

    @Autowired
    private SessionViewRepository views;

    private void deliver(String json, String destination) {
        inbound.send(MessageBuilder
                .withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeader("contentType", "application/json")
                .build(), destination);
    }

    private void sessionAdded(String occurrenceId, String sessionId, String title) {
        deliver("""
                {
                  "eventId": "%s",
                  "occurredAt": "2026-08-22T10:15:30Z",
                  "sessionId": "%s",
                  "conferenceEventId": "conf-1",
                  "title": "%s",
                  "track": "ML Infrastructure",
                  "startsAt": "2026-09-14T09:00:00",
                  "endsAt": "2026-09-14T10:30:00",
                  "speakerId": "spk-1",
                  "priceAmount": 49.50,
                  "priceCurrency": "AUD"
                }
                """.formatted(occurrenceId, sessionId, title), "session-added");
    }

    private void capacityChanged(String occurrenceId, String sessionId, int capacity) {
        deliver("""
                {
                  "eventId": "%s",
                  "occurredAt": "2026-08-22T10:15:31Z",
                  "sessionId": "%s",
                  "conferenceEventId": "conf-1",
                  "seatCapacity": %d
                }
                """.formatted(occurrenceId, sessionId, capacity), "session-capacity-changed");
    }

    @Test
    void replicatesASessionFromItsDescriptionEvent() {
        sessionAdded("occ-10", "ses-10", "Observability in practice");

        SessionView view = views.findById("ses-10").orElseThrow();
        assertThat(view.getEventId()).isEqualTo("conf-1");
        assertThat(view.getTitle()).isEqualTo("Observability in practice");
        assertThat(view.getTimeSlot().start()).isEqualTo(LocalDateTime.parse("2026-09-14T09:00"));
        assertThat(view.getTimeSlot().end()).isEqualTo(LocalDateTime.parse("2026-09-14T10:30"));
        assertThat(view.getPrice().amount()).isEqualByComparingTo("49.50");
        assertThat(view.getPrice().currency()).isEqualTo("AUD");
        assertThat(view.isComplete()).as("described, but its capacity has not arrived").isFalse();
    }

    @Test
    void takesCapacityOnlyFromTheCapacityTopic() {
        sessionAdded("occ-11", "ses-11", "Observability in practice");
        assertThat(views.findById("ses-11").orElseThrow().getCapacity())
                .as("unknown until the capacity topic speaks").isNull();

        capacityChanged("occ-12", "ses-11", 120);

        assertThat(views.findById("ses-11").orElseThrow().getCapacity()).isEqualTo(120);
    }

    @Test
    void appliesTheLatestCapacity() {
        sessionAdded("occ-13", "ses-12", "Observability in practice");
        capacityChanged("occ-14", "ses-12", 120);

        capacityChanged("occ-15", "ses-12", 80);

        assertThat(views.findById("ses-12").orElseThrow().getCapacity()).isEqualTo(80);
    }

    @Test
    void toleratesCapacityArrivingBeforeTheDescription() {
        capacityChanged("occ-16", "ses-13", 120);

        SessionView stub = views.findById("ses-13").orElseThrow();
        assertThat(stub.getCapacity()).isEqualTo(120);
        assertThat(stub.isComplete())
                .as("a capacity-only row does not yet describe a session")
                .isFalse();

        sessionAdded("occ-17", "ses-13", "Observability in practice");

        SessionView completed = views.findById("ses-13").orElseThrow();
        assertThat(completed.isComplete()).isTrue();
        assertThat(completed.getCapacity())
                .as("the description must not reset the capacity")
                .isEqualTo(120);
    }

    @Test
    void isUnharmedByRedelivery() {
        sessionAdded("occ-18", "ses-14", "Observability in practice");
        capacityChanged("occ-19", "ses-14", 120);

        sessionAdded("occ-18", "ses-14", "Observability in practice");
        capacityChanged("occ-19", "ses-14", 120);

        SessionView view = views.findById("ses-14").orElseThrow();
        assertThat(view.getCapacity()).isEqualTo(120);
        assertThat(view.getTitle()).isEqualTo("Observability in practice");
    }
}
