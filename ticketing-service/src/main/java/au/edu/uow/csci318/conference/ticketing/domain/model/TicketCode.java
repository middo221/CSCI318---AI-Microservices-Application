package au.edu.uow.csci318.conference.ticketing.domain.model;

import jakarta.persistence.Embeddable;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The opaque, scannable credential a ticket is presented by.
 *
 * <p>Generated on issue and never derived from anything the attendee supplied. A code that encoded
 * the registration or session id would let anybody holding one ticket work out the codes of every
 * other ticket on the same registration, so the value is nine bytes of {@link SecureRandom} and
 * nothing else.
 *
 * <p><strong>URL-safe is a hard constraint, not a preference.</strong> The code is a path segment
 * in {@code GET /api/v1/tickets/{ticketCode}} and the Kafka message key of every ticket event.
 * Base64 with the URL alphabet and no padding keeps it safe in both places without escaping. The
 * readable {@code TKT-} prefix costs four characters and makes a code recognisable at a glance in
 * Kafka UI during the demonstration.
 *
 * <p>Uniqueness is a database constraint rather than an application check — see the unique column
 * on {@code Ticket.ticketCode}. Nine random bytes make a collision vanishingly unlikely, and the
 * constraint is what makes "vanishingly unlikely" into "impossible".
 */
@Embeddable
public record TicketCode(String value) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int RANDOM_BYTES = 9;

    public TicketCode {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ticket code must be supplied");
        }
        value = value.trim();
        if (!URL_SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("ticket code must be URL-safe, was " + value);
        }
    }

    /** Mints a fresh code. Nine bytes encode to twelve characters with no padding. */
    public static TicketCode generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return new TicketCode("TKT-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
