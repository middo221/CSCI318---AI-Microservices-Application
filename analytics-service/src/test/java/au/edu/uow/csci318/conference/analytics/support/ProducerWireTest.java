package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.analytics.infrastructure.streams.EventSerdes;
import au.edu.uow.csci318.conference.common.event.EventPublished;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerWireTest {

    /**
     * Verbatim from docs/verification/2026-m1-kafka-hop.md — read off the live broker with
     * kafka-console-consumer during the M1 verification, written by event-catalog-service.
     */
    private static final String OBSERVED_ON_THE_BROKER =
            "{\"eventId\":\"dc8d46db-2562-4c8a-9d7a-92718a454925\","
                    + "\"occurredAt\":\"2026-08-22T06:40:56.632721200Z\","
                    + "\"conferenceEventId\":\"120a30b0-89b9-4f46-8b21-10223b4486f0\","
                    + "\"name\":\"SoftEng Summit\",\"startDate\":\"2026-09-14\",\"endDate\":\"2026-09-16\"}";

    @Test
    void writesByteForByteWhatAProducerWasSeenToWrite() {
        EventPublished event = new EventPublished("dc8d46db-2562-4c8a-9d7a-92718a454925",
                Instant.parse("2026-08-22T06:40:56.632721200Z"), "120a30b0-89b9-4f46-8b21-10223b4486f0",
                "SoftEng Summit", LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-16"));

        assertThat(new String(ProducerWire.bytes(event), StandardCharsets.UTF_8)).isEqualTo(OBSERVED_ON_THE_BROKER);
    }

    @Test
    void writesMoneyAsANumberAndInstantsAsText() throws Exception {
        JsonNode payload = new ObjectMapper().readTree(ProducerWire.bytes(TestEvents.registrationRequested("reg-1")));

        assertThat(payload.get("totalAmount").isNumber()).isTrue();
        assertThat(payload.get("holdExpiresAt").isTextual()).isTrue();
    }

    /**
     * Verbatim from the M5 live run (docs/verification/2026-m2-payment-saga.md), written by
     * registration-service and read off the broker with kafka-console-consumer.
     */
    private static final String REGISTRATION_REQUESTED_ON_THE_BROKER = "{\"eventId\":\"adb9e011-ceab-4d83-99a3-aed3f8f2f0c7\",\"occurredAt\":\"2026-10-09T04:17:26.884092200Z\",\"registrationId\":\"3cfac26e-bcf0-49a6-8a07-3795d47e4fab\",\"attendeeId\":\"att-1042\",\"conferenceEventId\":\"cf07d5d7-0b31-4afb-aa7f-ac6485a4cea1\",\"totalAmount\":148.50,\"currency\":\"AUD\",\"holdExpiresAt\":\"2026-10-09T04:17:46.884092200Z\",\"cardToken\":\"TOK_OK\",\"firstSessionStartsAt\":\"2026-09-13T23:00:00Z\"}";

    @Test
    void readsARegistrationRequestedExactlyAsTheProducerWroteItLive() throws Exception {
        JsonNode payload = new ObjectMapper().readTree(REGISTRATION_REQUESTED_ON_THE_BROKER);

        assertThat(payload.get("totalAmount").isNumber()).isTrue();
        assertThat(payload.get("holdExpiresAt").isTextual()).isTrue();
        assertThat(payload.get("registrationId").asText()).isNotBlank();
        assertThat(EventSerdes.json(RegistrationRequested.class).deserializer()
                .deserialize("registration-requested", REGISTRATION_REQUESTED_ON_THE_BROKER.getBytes(StandardCharsets.UTF_8))
                .registrationId()).isEqualTo(payload.get("registrationId").asText());
    }
}
