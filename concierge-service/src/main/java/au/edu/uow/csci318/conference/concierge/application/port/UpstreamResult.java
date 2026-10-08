package au.edu.uow.csci318.conference.concierge.application.port;

/**
 * What came back from a call to another service, when the difference between "no" and "no
 * answer" matters.
 *
 * <p>The agenda builder's ports collapse every failure into an empty result, which is right for
 * searching a programme. The support agent cannot do the same. "Registration not found" and
 * "registration service unreachable" call for opposite replies to an attendee - "check the id"
 * against "an organiser will follow this up" - and a refund refused with
 * {@code REGISTRATION_NOT_CONFIRMED} must be explained, not reported as an outage. So these ports
 * say which of the three happened (plan refinement 5).
 *
 * @param <T> what a successful call yields
 */
public sealed interface UpstreamResult<T> {

    /** The service answered with what was asked for. */
    record Ok<T>(T value) implements UpstreamResult<T> {
    }

    /** The service answered, and said no, in the platform's {@code {code, message}} shape. */
    record Rejected<T>(String code, String message) implements UpstreamResult<T> {

        public Rejected {
            code = code == null || code.isBlank() ? "UNKNOWN" : code;
            message = message == null ? "" : message;
        }
    }

    /** No usable answer: unreachable, timed out, a 5xx, or a body that would not parse. */
    record Unavailable<T>(String detail) implements UpstreamResult<T> {
    }
}
