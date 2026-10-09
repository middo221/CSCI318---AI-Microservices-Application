package au.edu.uow.csci318.conference.analytics.domain.readmodel;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;

import java.util.List;
import java.util.Optional;

/**
 * Reads the RT1 fill-rate table (FR-14, FR-15). A port: the domain says what it needs, the
 * infrastructure layer answers from a Kafka Streams state store.
 *
 * <p>Every method throws {@link ReadModelUnavailableException} while the store cannot be queried.
 */
public interface SessionFillRates {

    /** Empty when the session has no capacity on record. */
    Optional<FillRate> find(String sessionId);

    List<FillRate> all();
}
