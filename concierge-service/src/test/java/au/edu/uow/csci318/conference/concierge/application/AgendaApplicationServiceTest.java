package au.edu.uow.csci318.conference.concierge.application;

import au.edu.uow.csci318.conference.concierge.application.agent.AgendaAgentFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaToolsFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.ProposedAgenda;
import au.edu.uow.csci318.conference.concierge.application.agent.ProposedAgenda.ProposedItem;
import au.edu.uow.csci318.conference.concierge.application.command.BuildAgendaCommand;
import au.edu.uow.csci318.conference.concierge.domain.service.ClashDetector;
import au.edu.uow.csci318.conference.concierge.infrastructure.config.ConciergeProperties;
import au.edu.uow.csci318.conference.concierge.support.FakeCatalog;
import au.edu.uow.csci318.conference.concierge.support.FakeRegistrations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static au.edu.uow.csci318.conference.concierge.support.Sessions.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guarantees the agenda feature actually makes.
 *
 * <p>Every test here drives a model that has already decided what it wants to recommend. The
 * question under test is never whether the model chose well. It is whether, given that the model
 * chose badly, anything bad reaches the attendee.
 */
class AgendaApplicationServiceTest {

    private static final String INTERESTS = "streams, observability, anything by spk-1";

    private AgendaToolsFactory toolsFactory;

    @BeforeEach
    void setUp() {
        FakeCatalog catalog = new FakeCatalog().with(
                at("ses-1", "Streams deep dive", "09:00", "10:30", "PLATFORM", "spk-1"),
                at("ses-2", "Observability in practice", "10:00", "11:00", "PLATFORM", "spk-2"),
                at("ses-3", "Afternoon workshop", "14:00", "15:30", "PRODUCT", "spk-1"),
                at("ses-4", "Late afternoon panel", "16:00", "17:00", "PRODUCT", "spk-3"),
                at("ses-5", "Evening keynote", "18:00", "19:00", "PRODUCT", "spk-4"));
        toolsFactory = new AgendaToolsFactory(catalog, new FakeRegistrations(), new ClashDetector());
    }

    /** A model that searches the whole programme, then returns exactly {@code answer}. */
    private AgendaAgentFactory searchesEverythingAndAnswers(ProposedAgenda answer) {
        return tools -> (attendeeId, interests) -> {
            tools.searchSessions(null, null, null, null);
            return answer;
        };
    }

    /** A model that searches nothing at all, then returns exactly {@code answer}. */
    private AgendaAgentFactory searchesNothingAndAnswers(ProposedAgenda answer) {
        return tools -> (attendeeId, interests) -> answer;
    }

    private AgendaApplicationService serviceDrivenBy(AgendaAgentFactory agents, int maxItems) {
        return new AgendaApplicationService(toolsFactory, agents, new ClashDetector(),
                new ConciergeProperties("http://catalog.test", "http://registration.test",
                        "http://payment.test", "http://ticketing.test", maxItems,
                        new java.math.BigDecimal("200.00"), "AUD"));
    }

    private AgendaResult build(AgendaAgentFactory agents) {
        return serviceDrivenBy(agents, 8).buildAgenda(new BuildAgendaCommand("att-1042", INTERESTS));
    }

    @Test
    void passesACleanAgendaThroughInTheModelsOwnOrder() {
        AgendaResult result = build(searchesEverythingAndAnswers(new ProposedAgenda(
                List.of(new ProposedItem("ses-3", "Matches your interest in spk-1."),
                        new ProposedItem("ses-1", "Core streams content.")),
                "Two sessions.")));

        assertThat(result.attendeeId()).isEqualTo("att-1042");
        assertThat(result.summary()).isEqualTo("Two sessions.");
        assertThat(result.items()).extracting(AgendaResult.AgendaItem::sessionId)
                .containsExactly("ses-3", "ses-1");
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void takesEveryDisplayedFactFromTheCatalogueRatherThanTheModel() {
        AgendaResult result = build(searchesEverythingAndAnswers(new ProposedAgenda(
                List.of(new ProposedItem("ses-1", "Because you like streams.")), "One session.")));

        AgendaResult.AgendaItem item = result.items().getFirst();
        assertThat(item.title()).isEqualTo("Streams deep dive");
        assertThat(item.track()).isEqualTo("PLATFORM");
        assertThat(item.speakerId()).isEqualTo("spk-1");
        assertThat(item.startsAt().toString()).endsWith("T09:00");
        assertThat(item.endsAt().toString()).endsWith("T10:30");
        assertThat(item.rationale()).isEqualTo("Because you like streams.");
    }

    @Test
    void dropsTheLaterOfTwoSessionsThatClash() {
        AgendaResult result = build(searchesEverythingAndAnswers(new ProposedAgenda(
                List.of(new ProposedItem("ses-1", "Preferred."),
                        new ProposedItem("ses-2", "Also good, but it overlaps.")),
                "Two sessions.")));

        assertThat(result.items()).extracting(AgendaResult.AgendaItem::sessionId)
                .containsExactly("ses-1");
        assertThat(result.notes()).singleElement().asString()
                .contains("ses-2")
                .contains("Streams deep dive");
    }

    /**
     * The test that makes the design argument real. The model never calls detectTimeClashes, so
     * nothing the prompt asked for has been honoured, and the result is still clash-free.
     */
    @Test
    void isClashFreeEvenWhenTheModelNeverChecksForClashes() {
        AgendaResult result = build(searchesEverythingAndAnswers(new ProposedAgenda(
                List.of(new ProposedItem("ses-1", "a"),
                        new ProposedItem("ses-2", "b"),
                        new ProposedItem("ses-3", "c")),
                "Three sessions.")));

        assertThat(result.items()).extracting(AgendaResult.AgendaItem::sessionId)
                .containsExactly("ses-1", "ses-3");
        assertThat(result.notes()).hasSize(1);
    }

    @Test
    void discardsASessionTheAgentNeverRetrieved() {
        AgendaResult result = build(searchesEverythingAndAnswers(new ProposedAgenda(
                List.of(new ProposedItem("ses-1", "Real."),
                        new ProposedItem("ses-invented", "A session that does not exist.")),
                "Two sessions.")));

        assertThat(result.items()).extracting(AgendaResult.AgendaItem::sessionId)
                .containsExactly("ses-1");
        assertThat(result.notes()).singleElement().asString().contains("ses-invented");
    }

    @Test
    void discardsEverythingWhenTheModelAnswersWithoutSearchingAtAll() {
        AgendaResult result = build(searchesNothingAndAnswers(new ProposedAgenda(
                List.of(new ProposedItem("ses-1", "Confidently wrong.")), "One session.")));

        assertThat(result.items()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("ses-1");
    }

    @Test
    void keepsOnlyTheFirstOfADuplicatedSession() {
        AgendaResult result = build(searchesEverythingAndAnswers(new ProposedAgenda(
                List.of(new ProposedItem("ses-1", "First mention."),
                        new ProposedItem("ses-1", "The same session again.")),
                "One session, twice.")));

        assertThat(result.items()).hasSize(1);
        assertThat(result.notes()).singleElement().asString()
                .contains("ses-1")
                .contains("already");
    }

    @Test
    void capsTheAgendaAtTheConfiguredSize() {
        AgendaResult result = serviceDrivenBy(searchesEverythingAndAnswers(new ProposedAgenda(
                List.of(new ProposedItem("ses-1", "a"),
                        new ProposedItem("ses-3", "b"),
                        new ProposedItem("ses-4", "c"),
                        new ProposedItem("ses-5", "d")),
                "Four sessions.")), 2)
                .buildAgenda(new BuildAgendaCommand("att-1042", INTERESTS));

        assertThat(result.items()).extracting(AgendaResult.AgendaItem::sessionId)
                .containsExactly("ses-1", "ses-3");
        assertThat(result.notes()).singleElement().asString().contains("2");
    }

    @Test
    void returnsAnEmptyAgendaWhenTheModelRecommendsNothing() {
        AgendaResult result = build(searchesEverythingAndAnswers(
                new ProposedAgenda(List.of(), "Nothing matched what you asked for.")));

        assertThat(result.items()).isEmpty();
        assertThat(result.notes()).isEmpty();
        assertThat(result.summary()).isEqualTo("Nothing matched what you asked for.");
    }

    @Test
    void propagatesTheAbsenceOfAModelSoTheApiCanReportIt() {
        AgendaAgentFactory noModel = tools -> {
            throw new LlmUnavailableException("no chat model configured");
        };

        assertThatThrownBy(() -> build(noModel)).isInstanceOf(LlmUnavailableException.class);
    }
}
