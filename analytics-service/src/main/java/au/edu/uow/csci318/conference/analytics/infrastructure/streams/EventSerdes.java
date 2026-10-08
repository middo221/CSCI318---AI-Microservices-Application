package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Serde;
import org.springframework.kafka.support.serializer.JsonSerde;

/**
 * The one place this service decides how JSON crosses the Kafka boundary — inputs, outputs and
 * state stores alike.
 *
 * <p>Left alone, the binder would fall back to spring-kafka's default {@code JsonSerde}, whose
 * mapper writes {@code Instant}s as decimal seconds and whose serialiser stamps a {@code __TypeId__}
 * header naming a class in this service. Neither matches the rest of Converge, where every producer
 * writes ISO-8601 text and no type header. These serdes:
 * <ul>
 *   <li>write {@code Instant}s as ISO-8601 text, matching what the producers write;</li>
 *   <li>ignore properties they do not know, so a producer adding a field breaks nothing here;</li>
 *   <li>write no type header, and ignore one if a producer ever sends it — the topic decides the
 *       type, and a header naming another service's class would otherwise fail to load.</li>
 * </ul>
 * {@code StreamsTopologyConfiguration} registers one as a {@code Serde<T>} bean per event type so
 * the binder uses it; {@code BinderWiringTest} proves that it does.
 */
public final class EventSerdes {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventSerdes() {
    }

    public static <T> Serde<T> json(Class<T> type) {
        JsonSerde<T> serde = new JsonSerde<>(type, MAPPER);
        serde.noTypeInfo();
        serde.ignoreTypeHeaders();
        return serde;
    }
}
