package au.edu.uow.csci318.conference.concierge.support;

import au.edu.uow.csci318.conference.concierge.domain.model.RefundQuote;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationDetails;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Test fixtures for the support agent: registrations, policy quotes and fixed instants.
 *
 * <p>Every registration and quote in the support suite is built through here, so a change to
 * {@link RegistrationDetails} or {@link RefundQuote} lands in one place.
 */
public final class SupportFixtures {

    /** The attendee every support conversation in the suite is held with. */
    public static final String ME = "att-1042";

    /** An attendee whose registrations {@link #ME} must never be able to see or touch. */
    public static final String SOMEONE_ELSE = "att-2000";

    /** When registration says it accepted a refund request. */
    public static final Instant REQUESTED_AT = Instant.parse("2026-10-09T01:02:03Z");

    /** The clock escalations are stamped with. */
    public static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-09T02:00:00Z"), ZoneOffset.UTC);

    private SupportFixtures() {
    }

    /** A confirmed, paid registration of one session, with no refund requested. */
    public static RegistrationDetails confirmed(String registrationId, String attendeeId, String amount) {
        return new RegistrationDetails(registrationId, attendeeId, "evt-1", "CONFIRMED",
                new BigDecimal(amount), "AUD", List.of("ses-1"), false);
    }

    /** Thirty days or more before the event: everything back. */
    public static RefundQuote fullRefund(String registrationId, String paid) {
        return new RefundQuote(registrationId, "po-" + registrationId, new BigDecimal(paid), "AUD",
                45, 100, new BigDecimal(paid), "FULL_30_DAYS_OR_MORE", true);
    }

    /** Under seven days before the event: nothing back. */
    public static RefundQuote noRefund(String registrationId, String paid) {
        return new RefundQuote(registrationId, "po-" + registrationId, new BigDecimal(paid), "AUD",
                3, 0, new BigDecimal("0.00"), "NONE_UNDER_7_DAYS", false);
    }

    /** Payment does not know when the event is, so {@code daysUntilEvent} is null and nothing is refundable. */
    public static RefundQuote unknownDate(String registrationId, String paid) {
        return new RefundQuote(registrationId, "po-" + registrationId, new BigDecimal(paid), "AUD",
                null, 0, new BigDecimal("0.00"), "EVENT_DATE_UNKNOWN", false);
    }

    /** A quote with the money, currency and eligibility chosen by the test. */
    public static RefundQuote quote(String registrationId, String paid, String refund,
                                    String currency, boolean eligible) {
        return new RefundQuote(registrationId, "po-" + registrationId, new BigDecimal(paid), currency,
                45, 100, new BigDecimal(refund), "FULL_30_DAYS_OR_MORE", eligible);
    }
}
