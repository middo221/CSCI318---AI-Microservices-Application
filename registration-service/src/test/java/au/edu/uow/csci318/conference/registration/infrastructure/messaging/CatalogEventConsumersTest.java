package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the consuming half of the Kafka hop.
 *
 * <p>Messages are pushed in as raw JSON bytes rather than as objects, because that is what
 * actually arrives from a broker. Sending a ready-made {@code EventPublished} instance would
 * skip message conversion entirely and hide the one thing most likely to be wrong.
 */
@SpringBootTest
@EnableTestBinder
class CatalogEventConsumersTest {

    private static final String EVENT_PUBLISHED_TOPIC = "event-published";

    @Autowired
    private InputDestination inbound;

    @Autowired
    private ProcessedEventStore processedEvents;

    @Autowired
    private ProcessedEventJpaRepository rows;

    @BeforeEach
    void clearProcessedEvents() {
        rows.deleteAll();
    }

    private static String eventPublishedJson(String occurrenceId, String conferenceEventId) {
        return """
                {
                  "eventId": "%s",
                  "occurredAt": "2026-08-22T10:15:30Z",
                  "conferenceEventId": "%s",
                  "name": "SoftEng Summit",
                  "startDate": "2026-09-14",
                  "endDate": "2026-09-16"
                }
                """.formatted(occurrenceId, conferenceEventId);
    }

    private void deliver(String json) {
        inbound.send(MessageBuilder
                .withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeader("contentType", "application/json")
                .build(), EVENT_PUBLISHED_TOPIC);
    }

    @Test
    void recordsAnEventPublishedDeliveryItHasNotSeenBefore() {
        deliver(eventPublishedJson("occ-1", "conf-1"));

        assertThat(processedEvents.hasProcessed("occ-1")).isTrue();
        assertThat(rows.findById("occ-1").orElseThrow().getEventType()).isEqualTo("event-published");
    }

    @Test
    void ignoresARedeliveryOfTheSameEvent() {
        deliver(eventPublishedJson("occ-2", "conf-2"));
        deliver(eventPublishedJson("occ-2", "conf-2"));

        assertThat(rows.count()).isEqualTo(1);
    }

    @Test
    void treatsDistinctOccurrencesOfTheSameConferenceEventAsDistinct() {
        deliver(eventPublishedJson("occ-3", "conf-3"));
        deliver(eventPublishedJson("occ-4", "conf-3"));

        assertThat(rows.count()).isEqualTo(2);
    }
}
