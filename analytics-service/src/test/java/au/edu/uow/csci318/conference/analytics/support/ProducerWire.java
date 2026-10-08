package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Writes an event exactly as the producing services put it on the wire.
 *
 * <p>Every producer publishes through {@code StreamBridge}, whose JSON converter uses Spring Boot's
 * Jackson defaults: well-known modules registered, dates as ISO-8601 strings, no type headers.
 * {@code ProducerWireTest} pins this helper byte for byte to a message read straight off the broker,
 * so a test that feeds these bytes to a topology is feeding it what the broker will.
 */
public final class ProducerWire {

    private static final ObjectMapper PRODUCER = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private ProducerWire() {
    }

    public static byte[] bytes(DomainEvent event) {
        try {
            return PRODUCER.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise " + event, e);
        }
    }
}
