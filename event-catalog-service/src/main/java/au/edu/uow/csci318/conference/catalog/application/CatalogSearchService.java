package au.edu.uow.csci318.conference.catalog.application;

import au.edu.uow.csci318.conference.catalog.application.command.SessionSearchQuery;
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;
import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import au.edu.uow.csci318.conference.catalog.domain.repository.SpeakerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Attendee-facing catalogue search (FR-05).
 *
 * <p>Only <strong>published</strong> events are searched. A draft programme is the organiser's
 * working copy and must never leak to attendees, which is why the filter is applied at the
 * repository call rather than left to the caller to remember.
 *
 * <p>Filtering happens in Java over loaded aggregates rather than in JPQL. A query straight onto
 * {@code Session} would read part of the {@code Event} aggregate without going through its root
 * — the boundary violation this design exists to avoid — and at 10 to 80 sessions the cost of
 * doing it properly is unmeasurable. At 10,000 sessions the answer would be different, and that
 * is worth saying out loud rather than pretending this scales unchanged.
 */
@Service
public class CatalogSearchService {

    /** A session together with the event it belongs to, since {@code Session} does not know. */
    public record Hit(String eventId, Session session) {
    }

    private final EventRepository events;
    private final SpeakerRepository speakers;

    public CatalogSearchService(EventRepository events, SpeakerRepository speakers) {
        this.events = events;
        this.speakers = speakers;
    }

    @Transactional(readOnly = true)
    public List<Hit> search(SessionSearchQuery query) {
        Set<String> speakerIds = resolveSpeakers(query.speaker());

        return events.findByStatus(EventStatus.PUBLISHED).stream()
                .flatMap(event -> event.getSessions().stream().map(s -> new Hit(event.getId(), s)))
                .filter(hit -> matchesTrack(hit.session(), query.track()))
                .filter(hit -> matchesDay(hit.session(), query))
                .filter(hit -> speakerIds == null || speakerIds.contains(hit.session().getSpeakerId()))
                .filter(hit -> matchesKeyword(hit.session(), query.keyword()))
                .toList();
    }

    /**
     * Resolves the {@code speaker} parameter to a set of ids.
     *
     * <p>Accepts either an exact speaker id, which is what the concierge agent will pass in M4, or
     * a case-insensitive fragment of a speaker's name, which is what a person types. Returns
     * {@code null} when no speaker filter was requested, so the caller can tell "do not filter"
     * apart from "filter matched nobody".
     */
    private Set<String> resolveSpeakers(String speaker) {
        if (speaker == null || speaker.isBlank()) {
            return null;
        }
        String needle = speaker.trim().toLowerCase();
        return speakers.findAll().stream()
                .filter(s -> s.getId().equals(speaker.trim())
                        || s.getName().toLowerCase().contains(needle))
                .map(Speaker::getId)
                .collect(Collectors.toSet());
    }

    private boolean matchesTrack(Session session, String track) {
        return track == null || track.isBlank() || session.getTrack().matches(track);
    }

    private boolean matchesDay(Session session, SessionSearchQuery query) {
        return query.day() == null || session.getTimeSlot().day().equals(query.day());
    }

    private boolean matchesKeyword(Session session, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String needle = keyword.trim().toLowerCase();
        return session.getTitle().toLowerCase().contains(needle)
                || session.getTrack().name().toLowerCase().contains(needle);
    }
}
