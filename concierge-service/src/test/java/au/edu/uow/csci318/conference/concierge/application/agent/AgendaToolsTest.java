package au.edu.uow.csci318.conference.concierge.application.agent;

import au.edu.uow.csci318.conference.concierge.domain.model.Clash;
import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;
import au.edu.uow.csci318.conference.concierge.domain.service.ClashDetector;
import au.edu.uow.csci318.conference.concierge.support.FakeCatalog;
import au.edu.uow.csci318.conference.concierge.support.FakeRegistrations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static au.edu.uow.csci318.conference.concierge.support.Sessions.at;
import static org.assertj.core.api.Assertions.assertThat;

class AgendaToolsTest {

    private FakeCatalog catalog;
    private FakeRegistrations registrations;
    private AgendaTools tools;

    @BeforeEach
    void setUp() {
        catalog = new FakeCatalog().with(
                at("ses-1", "Streams deep dive", "09:00", "10:30", "PLATFORM", "spk-1"),
                at("ses-2", "Observability in practice", "10:00", "11:00", "PLATFORM", "spk-2"),
                at("ses-3", "Afternoon workshop", "14:00", "15:30", "PRODUCT", "spk-1"));
        registrations = new FakeRegistrations()
                .withSeats("ses-1", "Streams deep dive", 12)
                .withSeats("ses-2", "Observability in practice", 0)
                .withRegistration("att-1042", "ses-3");
        tools = new AgendaTools(catalog, registrations, new ClashDetector());
    }

    @Test
    void passesEveryFacetThroughToTheCatalogue() {
        tools.searchSessions("PLATFORM", "2026-09-14", "spk-1", "streams");

        assertThat(catalog.queries()).containsExactly(
                new FakeCatalog.Query("PLATFORM", LocalDate.parse("2026-09-14"), "spk-1", "streams"));
    }

    @Test
    void treatsAnUnparseableDayAsNoDayFilterRatherThanFailing() {
        List<SessionSummary> found = tools.searchSessions(null, "next Tuesday", null, null);

        assertThat(catalog.queries()).containsExactly(
                new FakeCatalog.Query(null, null, null, null));
        assertThat(found).hasSize(3);
    }

    @Test
    void recordsEverySessionItReturnsInTheDossier() {
        tools.searchSessions("PLATFORM", null, null, null);

        assertThat(tools.dossier().keySet()).containsExactlyInAnyOrder("ses-1", "ses-2");
    }

    @Test
    void aRepeatedSearchDoesNotDuplicateDossierEntries() {
        tools.searchSessions("PLATFORM", null, null, null);
        tools.searchSessions(null, null, null, "observability");

        assertThat(tools.dossier()).hasSize(2);
        assertThat(tools.dossier().get("ses-2").title()).isEqualTo("Observability in practice");
    }

    @Test
    void reportsSeatsForASessionRegistrationTracks() {
        AgendaTools.AvailabilityAnswer answer = tools.getSessionAvailability("ses-1");

        assertThat(answer.known()).isTrue();
        assertThat(answer.availableSeats()).isEqualTo(12);
        assertThat(answer.full()).isFalse();
    }

    @Test
    void reportsAFullSessionAsFull() {
        AgendaTools.AvailabilityAnswer answer = tools.getSessionAvailability("ses-2");

        assertThat(answer.known()).isTrue();
        assertThat(answer.full()).isTrue();
        assertThat(answer.availableSeats()).isZero();
    }

    @Test
    void degradesRatherThanThrowingForASessionRegistrationHasNotReplicated() {
        AgendaTools.AvailabilityAnswer answer = tools.getSessionAvailability("ses-3");

        assertThat(answer.known()).isFalse();
        assertThat(answer.explanation()).isNotBlank();
    }

    @Test
    void readsTheRegistrationsAnAttendeeAlreadyHolds() {
        assertThat(tools.getAttendeeRegistrations("att-1042"))
                .singleElement()
                .satisfies(registration ->
                        assertThat(registration.sessionIds()).containsExactly("ses-3"));
    }

    @Test
    void findsAClashBetweenTwoSessionsItHasSearchedFor() {
        tools.searchSessions("PLATFORM", null, null, null);

        List<Clash> clashes = tools.detectTimeClashes(List.of("ses-1", "ses-2"));

        assertThat(clashes).hasSize(1);
        assertThat(clashes.getFirst().firstSessionId()).isEqualTo("ses-1");
        assertThat(clashes.getFirst().secondSessionId()).isEqualTo("ses-2");
    }

    @Test
    void findsNoClashBetweenSessionsThatDoNotOverlap() {
        tools.searchSessions(null, null, null, null);

        assertThat(tools.detectTimeClashes(List.of("ses-1", "ses-3"))).isEmpty();
    }

    @Test
    void ignoresSessionIdsItHasNeverSeen() {
        tools.searchSessions("PLATFORM", null, null, null);

        assertThat(tools.detectTimeClashes(List.of("ses-1", "ses-invented"))).isEmpty();
    }

    @Test
    void toleratesANullSessionIdList() {
        assertThat(tools.detectTimeClashes(null)).isEmpty();
    }
}
