package au.edu.uow.csci318.conference.registration.domain.service;

import au.edu.uow.csci318.conference.registration.domain.SessionFullException;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.HoldWindow;
import au.edu.uow.csci318.conference.registration.domain.model.SeatHold;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides which seats a registration may hold, across every requested session at once.
 *
 * <p>A <strong>domain service</strong> rather than a method on {@code Registration}, because the
 * decision spans several {@link SessionView} instances and — from M3 — the {@code Waitlist}
 * aggregate. It belongs to no single aggregate, which is precisely the case the pattern exists
 * for.
 *
 * <p>Deliberately a plain final class with no Spring annotations, so it is unit-testable without a
 * container. {@code SessionSchedulingPolicy} in the Programme context made the same choice.
 *
 * <p><strong>All or nothing.</strong> Every session is checked before any hold is created, so a
 * refusal leaves nothing half-allocated. See {@link SessionFullException} for why a full session
 * refuses the whole request in M2 rather than falling through to a waitlist.
 */
public final class SeatAllocationService {

    /**
     * Creates one hold per requested session.
     *
     * @param requested session id to seat count, in the order the attendee asked for them
     * @param views     the local replica rows for those sessions
     * @param window    how long the holds survive without payment
     * @param now       the reference instant expiry is measured from
     * @throws IllegalArgumentException if no sessions were requested
     * @throws SessionNotFoundException if a requested session is unknown or not yet described
     * @throws SessionFullException     if any requested session cannot take its seats
     */
    public List<SeatHold> allocate(Map<String, Integer> requested,
                                   Map<String, SessionView> views,
                                   HoldWindow window,
                                   Instant now) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("a registration must request at least one session");
        }

        // Check everything first: a refusal must leave nothing allocated.
        requested.forEach((sessionId, quantity) -> {
            SessionView view = viewFor(sessionId, views);
            if (!view.canAccommodate(quantity)) {
                throw new SessionFullException(sessionId, quantity, view.getAvailableSeats());
            }
        });

        Instant expiresAt = window.expiresFrom(now);
        List<SeatHold> holds = new ArrayList<>(requested.size());
        requested.forEach((sessionId, quantity) ->
                holds.add(SeatHold.hold(UUID.randomUUID().toString(), sessionId, quantity, expiresAt)));
        return holds;
    }

    private SessionView viewFor(String sessionId, Map<String, SessionView> views) {
        SessionView view = views.get(sessionId);
        if (view == null || !view.isComplete()) {
            throw new SessionNotFoundException(sessionId);
        }
        return view;
    }
}
