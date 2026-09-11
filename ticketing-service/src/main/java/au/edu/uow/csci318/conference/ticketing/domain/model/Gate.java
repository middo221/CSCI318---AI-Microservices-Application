package au.edu.uow.csci318.conference.ticketing.domain.model;

import jakarta.persistence.Embeddable;

/**
 * The door an attendee was admitted through.
 *
 * <p>A single-field value object rather than a bare {@code String}, because the gate id is not an
 * arbitrary label: after the RT2 topology re-keys {@code attendee-checked-in} it becomes the
 * partition key the whole gate-congestion aggregation groups on. A stray leading space would split
 * one physical door's throughput across two keys and quietly halve both numbers, so trimming
 * belongs here, once, rather than at every call site that builds one.
 */
@Embeddable
public record Gate(String gateId) {

    public Gate {
        if (gateId == null || gateId.isBlank()) {
            throw new IllegalArgumentException("gate id must be supplied");
        }
        gateId = gateId.trim();
    }
}
