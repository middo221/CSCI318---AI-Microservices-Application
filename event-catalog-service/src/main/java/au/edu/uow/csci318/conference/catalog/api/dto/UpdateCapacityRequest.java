package au.edu.uow.csci318.conference.catalog.api.dto;

import jakarta.validation.constraints.Positive;

/** Wire contract for {@code PUT /api/v1/events/{id}/sessions/{sessionId}/capacity}. */
public record UpdateCapacityRequest(
        @Positive(message = "must be greater than zero") int seatCapacity) {
}
