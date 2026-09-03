package au.edu.uow.csci318.conference.concierge.application.agent;

import au.edu.uow.csci318.conference.concierge.application.port.CatalogPort;
import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.domain.model.Clash;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;
import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;
import au.edu.uow.csci318.conference.concierge.domain.service.ClashDetector;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four actions the Agenda Builder agent is allowed to take.
 *
 * <p>This is the agent's entire reach into the platform. There is no other path: the agent cannot
 * reach a database, cannot construct a URL, and cannot call a service this class does not already
 * call. Narrowing the surface to four methods is what makes the blast radius of a bad model
 * decision small enough to reason about.
 *
 * <p><strong>Not a bean, and not shared.</strong> Each instance accumulates a {@link #dossier} of
 * the sessions it has actually fetched during one conversation, and
 * {@code AgendaApplicationService} later resolves the model's answer against it. Two attendees
 * sharing an instance would mean one seeing sessions found for the other, so instances are minted
 * per request by {@link AgendaToolsFactory}.
 *
 * <p>Every method degrades rather than throwing. An exception thrown out of a tool becomes an
 * error message in the model's context and usually derails the whole run, so a failure that the
 * model can route around is worth more than a faithful stack trace.
 */
public class AgendaTools {

    private static final Logger log = LoggerFactory.getLogger(AgendaTools.class);

    private final CatalogPort catalog;
    private final RegistrationPort registrations;
    private final ClashDetector clashDetector;

    /** Insertion-ordered so the dossier reads in the order the agent discovered things. */
    private final Map<String, SessionSummary> dossier = new LinkedHashMap<>();

    public AgendaTools(CatalogPort catalog, RegistrationPort registrations,
                       ClashDetector clashDetector) {
        this.catalog = catalog;
        this.registrations = registrations;
        this.clashDetector = clashDetector;
    }

    /**
     * Every session this instance has seen come back from the catalogue, by id.
     *
     * <p>The provenance record for the whole run. A session that is not in here was never
     * retrieved, so any claim about it came from the model rather than from the platform.
     */
    public Map<String, SessionSummary> dossier() {
        return Map.copyOf(dossier);
    }

    @Tool("""
            Search the published conference programme. Every argument is an optional filter:
            omit one to leave that dimension unfiltered. Returns the matching sessions with their
            titles, tracks, speakers, start and end times, capacity and price. Call this before
            recommending anything - a session that has not come back from this tool is not a real
            session and must not appear in the agenda.""")
    public List<SessionSummary> searchSessions(
            @P(value = "Track name, for example PLATFORM or PRODUCT", required = false) String track,
            @P(value = "Conference day as an ISO date, for example 2026-09-14", required = false) String day,
            @P(value = "Speaker id or name fragment", required = false) String speaker,
            @P(value = "Free-text keyword matched against the session title", required = false) String keyword) {

        List<SessionSummary> found = catalog.searchSessions(track, parseDay(day), speaker, keyword);
        found.forEach(session -> dossier.putIfAbsent(session.sessionId(), session));
        return found;
    }

    @Tool("""
            Report how many seats remain at one session. Returns known=false when the registration
            service has no record of the session yet. Never put a session with full=true on an
            agenda.""")
    public AvailabilityAnswer getSessionAvailability(
            @P("The session id, exactly as returned by searchSessions") String sessionId) {

        return registrations.availabilityOf(sessionId)
                .map(summary -> new AvailabilityAnswer(sessionId, true,
                        summary.availableSeats(), summary.isFull(),
                        summary.isFull()
                                ? "This session is full."
                                : summary.availableSeats() + " seats remain."))
                .orElseGet(() -> new AvailabilityAnswer(sessionId, false, 0, false,
                        "The registration service is not tracking this session yet, "
                                + "so its remaining seats are unknown."));
    }

    @Tool("""
            List the registrations this attendee already holds, so the agenda does not repeat a
            session they have already booked.""")
    public List<RegistrationSummary> getAttendeeRegistrations(
            @P("The attendee id") String attendeeId) {
        return registrations.registrationsOf(attendeeId);
    }

    @Tool("""
            Check a proposed set of sessions for time conflicts. Returns one entry per pair that
            overlaps, and an empty list when the whole set can be attended. Call this before
            giving a final answer. Ids that did not come back from searchSessions are ignored.""")
    public List<Clash> detectTimeClashes(
            @P("Session ids to check against each other") List<String> sessionIds) {

        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<SessionSummary> known = sessionIds.stream()
                .map(dossier::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        return clashDetector.detect(known);
    }

    /**
     * An ISO date, or null when the model wrote something else.
     *
     * <p>Models write "next Tuesday" and "day 2" as readily as "2026-09-14". Refusing the call
     * would waste a round trip to tell it so; dropping the filter returns a wider result set that
     * it can narrow itself, which is the more useful failure.
     */
    private LocalDate parseDay(String day) {
        if (day == null || day.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(day.trim());
        } catch (DateTimeParseException ex) {
            log.debug("ignoring unparseable day filter from the agent: {}", day);
            return null;
        }
    }

    /**
     * What {@link #getSessionAvailability} tells the model.
     *
     * <p>Carries {@code known} and a sentence of prose as well as the number, because a bare
     * {@code 0} is ambiguous between "sold out" and "we could not find out" - and those two
     * warrant opposite behaviour from the agent.
     */
    public record AvailabilityAnswer(String sessionId,
                                     boolean known,
                                     int availableSeats,
                                     boolean full,
                                     String explanation) {
    }
}
