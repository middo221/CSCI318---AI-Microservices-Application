package au.edu.uow.csci318.conference.registration.domain.model;

import java.time.Instant;

/**
 * Where one waitlist entry stands — a snapshot computed by the {@link Waitlist}, never stored.
 *
 * @param position 1-based place among the entries still {@code WAITING}, contiguous as specification
 *                 §3.3 requires; {@code null} once the entry has been promoted or withdrawn
 */
public record WaitlistPlace(String waitlistEntryId,
                            String sessionId,
                            String attendeeId,
                            Integer position,
                            WaitlistStatus status,
                            Instant joinedAt) {
}
