package au.edu.uow.csci318.conference.concierge.domain.service;

import au.edu.uow.csci318.conference.concierge.domain.model.Clash;
import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static au.edu.uow.csci318.conference.concierge.support.Sessions.at;
import static org.assertj.core.api.Assertions.assertThat;

class ClashDetectorTest {

    private final ClashDetector detector = new ClashDetector();

    @Test
    void findsNothingInADisjointProgramme() {
        List<SessionSummary> programme = List.of(
                at("ses-1", "Morning", "09:00", "10:00"),
                at("ses-2", "Mid-morning", "10:00", "11:00"),
                at("ses-3", "Afternoon", "14:00", "15:00"));

        assertThat(detector.detect(programme)).isEmpty();
    }

    @Test
    void findsTheOnePairThatOverlaps() {
        List<SessionSummary> programme = List.of(
                at("ses-1", "Streams deep dive", "09:00", "10:30"),
                at("ses-2", "Observability", "10:00", "11:00"),
                at("ses-3", "Afternoon", "14:00", "15:00"));

        List<Clash> clashes = detector.detect(programme);

        assertThat(clashes).hasSize(1);
        assertThat(clashes.getFirst().firstSessionId()).isEqualTo("ses-1");
        assertThat(clashes.getFirst().secondSessionId()).isEqualTo("ses-2");
    }

    @Test
    void findsEveryPairInAMutuallyOverlappingTriple() {
        List<SessionSummary> programme = List.of(
                at("ses-1", "A", "09:00", "12:00"),
                at("ses-2", "B", "10:00", "11:00"),
                at("ses-3", "C", "10:30", "13:00"));

        assertThat(detector.detect(programme)).hasSize(3);
    }

    @Test
    void describesAClashInTermsAnAgentCanRelay() {
        List<Clash> clashes = detector.detect(List.of(
                at("ses-1", "Streams deep dive", "09:00", "10:30"),
                at("ses-2", "Observability", "10:00", "11:00")));

        assertThat(clashes.getFirst().describe())
                .contains("Streams deep dive")
                .contains("Observability");
    }

    @Test
    void reportsTheAcceptedSessionACandidateCollidesWith() {
        List<SessionSummary> accepted = List.of(
                at("ses-1", "Morning", "09:00", "10:30"),
                at("ses-2", "Afternoon", "14:00", "15:00"));

        Optional<SessionSummary> conflict =
                detector.firstConflict(at("ses-9", "Late morning", "10:00", "11:00"), accepted);

        assertThat(conflict).isPresent();
        assertThat(conflict.get().sessionId()).isEqualTo("ses-1");
    }

    @Test
    void reportsNoConflictWhenTheCandidateFits() {
        List<SessionSummary> accepted = List.of(at("ses-1", "Morning", "09:00", "10:00"));

        assertThat(detector.firstConflict(at("ses-9", "Next", "10:00", "11:00"), accepted))
                .isEmpty();
    }

    @Test
    void clashFreePrefixKeepsTheEarlierListedSessionOfAnOverlappingPair() {
        List<SessionSummary> proposed = List.of(
                at("ses-1", "Preferred", "09:00", "10:30"),
                at("ses-2", "Collides with the preferred one", "10:00", "11:00"),
                at("ses-3", "Fits", "11:00", "12:00"));

        assertThat(detector.clashFreePrefix(proposed))
                .extracting(SessionSummary::sessionId)
                .containsExactly("ses-1", "ses-3");
    }

    @Test
    void clashFreePrefixIsIdempotentOnAnAlreadyCleanList() {
        List<SessionSummary> proposed = List.of(
                at("ses-1", "A", "09:00", "10:00"),
                at("ses-2", "B", "10:00", "11:00"));

        assertThat(detector.clashFreePrefix(proposed)).isEqualTo(proposed);
    }

    @Test
    void handlesAnEmptyProgramme() {
        assertThat(detector.detect(List.of())).isEmpty();
        assertThat(detector.clashFreePrefix(List.of())).isEmpty();
    }
}
