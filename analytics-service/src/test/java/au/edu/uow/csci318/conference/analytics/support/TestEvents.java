package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.common.event.RegistrationExpired;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Builds every event this service consumes, with fixed timestamps so assertions are exact. */
public final class TestEvents {

    public static final Instant T0 = Instant.parse("2026-10-09T10:00:00Z");

    private TestEvents() {
    }

    public static SessionCapacityChanged capacity(String sessionId, int seats) {
        return new SessionCapacityChanged(id(), T0, sessionId, "evt-1", seats);
    }

    public static SeatsReserved reserved(String sessionId, int quantity) {
        return new SeatsReserved(id(), T0, "reg-" + id(), sessionId, quantity);
    }

    public static SeatsReleased released(String sessionId, int quantity) {
        return new SeatsReleased(id(), T0, "reg-" + id(), sessionId, quantity);
    }

    public static AttendeeCheckedIn checkedIn(String sessionId, String gateId, Instant queuedAt, Instant checkedInAt) {
        return new AttendeeCheckedIn(id(), checkedInAt, "TKT-" + id(), sessionId, gateId, queuedAt, checkedInAt);
    }

    /** Valid as a record — the compact constructor only rejects a check-in before its queue time. */
    public static AttendeeCheckedIn checkedInWithoutQueueTime(String sessionId, String gateId, Instant checkedInAt) {
        return new AttendeeCheckedIn(id(), checkedInAt, "TKT-" + id(), sessionId, gateId, null, checkedInAt);
    }

    /**
     * <strong>The only place in analytics-service that constructs {@code RegistrationRequested}.</strong>
     * The {@code feat/m3-refunds-waitlist} branch adds a trailing {@code Instant firstSessionStartsAt}
     * component; when the two branches meet, this one call is the whole fix. Do not inline it.
     */
    public static RegistrationRequested registrationRequested(String registrationId) {
        return new RegistrationRequested(id(), T0, registrationId, "att-1042", "evt-1",
                new BigDecimal("148.50"), "AUD", T0.plusSeconds(120), "TOK_OK");
    }

    public static PaymentSucceeded paymentSucceeded(String registrationId) {
        return new PaymentSucceeded(id(), T0, "pay-" + registrationId, registrationId, new BigDecimal("148.50"), "AUD");
    }

    public static PaymentFailed paymentFailed(String registrationId) {
        return new PaymentFailed(id(), T0, "pay-" + registrationId, registrationId, "CARD_DECLINED");
    }

    public static RegistrationExpired registrationExpired(String registrationId) {
        return new RegistrationExpired(id(), T0, registrationId, "att-1042");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
