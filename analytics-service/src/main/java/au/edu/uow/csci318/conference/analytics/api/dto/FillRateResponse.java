package au.edu.uow.csci318.conference.analytics.api.dto;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;

/** FR-14: one session's live fill rate. {@code fillRate} may exceed 1.0 for a session over capacity. */
public record FillRateResponse(String sessionId, int reservedSeats, int seatCapacity, double fillRate) {

    public static FillRateResponse from(FillRate rate) {
        return new FillRateResponse(rate.sessionId(), rate.reservedSeats(), rate.seatCapacity(), rate.fillRate());
    }
}
