package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Every serde this service uses, against the bytes the producers actually write. */
class EventSerdesTest {

    private static final String TOPIC = "any-topic";

    @Test
    void readsSeatsReservedAsRegistrationWritesIt() {
        SeatsReserved sent = TestEvents.reserved("ses-1", 3);

        assertThat(EventSerdes.json(SeatsReserved.class).deserializer().deserialize(TOPIC, ProducerWire.bytes(sent)))
                .isEqualTo(sent);
    }

    @Test
    void readsSeatsReleasedAsRegistrationWritesIt() {
        SeatsReleased sent = TestEvents.released("ses-1", 2);

        assertThat(EventSerdes.json(SeatsReleased.class).deserializer().deserialize(TOPIC, ProducerWire.bytes(sent)))
                .isEqualTo(sent);
    }

    @Test
    void readsSessionCapacityChangedAsTheCatalogueWritesIt() {
        SessionCapacityChanged sent = TestEvents.capacity("ses-1", 120);

        assertThat(EventSerdes.json(SessionCapacityChanged.class).deserializer()
                .deserialize(TOPIC, ProducerWire.bytes(sent))).isEqualTo(sent);
    }

    @Test
    void readsAttendeeCheckedInAsTicketingWritesIt() {
        Instant checkedInAt = TestEvents.T0.plusSeconds(90);
        AttendeeCheckedIn sent = TestEvents.checkedIn("ses-1", "north-1", checkedInAt.minusSeconds(75), checkedInAt);

        assertThat(EventSerdes.json(AttendeeCheckedIn.class).deserializer()
                .deserialize(TOPIC, ProducerWire.bytes(sent))).isEqualTo(sent);
    }

    @Test
    void ignoresAFieldAProducerAddsLater() {
        byte[] withExtraField = ("{\"eventId\":\"e-1\",\"occurredAt\":\"2026-10-09T10:00:00Z\","
                + "\"registrationId\":\"reg-1\",\"sessionId\":\"ses-1\",\"quantity\":2,\"addedLater\":true}")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(EventSerdes.json(SeatsReserved.class).deserializer().deserialize(TOPIC, withExtraField).quantity())
                .isEqualTo(2);
    }

    /** spring-kafka's default deserialiser would try to load this class and fail (binder fact B9). */
    @Test
    void neverLetsATypeHeaderOverrideTheTopicsType() {
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", "au.edu.uow.csci318.conference.registration.domain.Elsewhere".getBytes(StandardCharsets.UTF_8));
        SeatsReserved sent = TestEvents.reserved("ses-1", 3);

        assertThat(EventSerdes.json(SeatsReserved.class).deserializer().deserialize(TOPIC, headers, ProducerWire.bytes(sent)))
                .isEqualTo(sent);
    }

    @Test
    void writesInstantsAsIsoTextAndNoTypeHeader() throws Exception {
        RecordHeaders headers = new RecordHeaders();
        SessionCapacityAlert alert = SessionCapacityAlert.raise(FillRate.of("ses-1", 9, 10), 0.9, TestEvents.T0);

        byte[] written = EventSerdes.json(SessionCapacityAlert.class).serializer().serialize(TOPIC, headers, alert);

        assertThat(new ObjectMapper().readTree(written).get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(headers.lastHeader("__TypeId__")).isNull();
    }
}
