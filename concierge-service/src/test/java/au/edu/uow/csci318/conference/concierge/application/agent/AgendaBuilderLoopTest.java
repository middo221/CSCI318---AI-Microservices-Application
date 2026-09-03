package au.edu.uow.csci318.conference.concierge.application.agent;

import au.edu.uow.csci318.conference.concierge.domain.service.ClashDetector;
import au.edu.uow.csci318.conference.concierge.support.FakeCatalog;
import au.edu.uow.csci318.conference.concierge.support.FakeRegistrations;
import au.edu.uow.csci318.conference.concierge.support.ScriptedChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static au.edu.uow.csci318.conference.concierge.support.Sessions.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan-act-observe loop itself: does LangChain4j route the model's tool calls into our tools,
 * and does the final structured answer come back as a typed record?
 *
 * <p>Asserts on the tools that ran and the arguments they ran with, not only on the answer. An
 * agent that returns the right shape having called nothing is not an agent.
 */
class AgendaBuilderLoopTest {

    private FakeCatalog catalog;
    private AgendaTools tools;

    @BeforeEach
    void setUp() {
        catalog = new FakeCatalog().with(
                at("ses-1", "Streams deep dive", "09:00", "10:30", "PLATFORM", "spk-1"),
                at("ses-2", "Observability in practice", "10:00", "11:00", "PLATFORM", "spk-2"),
                at("ses-3", "Afternoon workshop", "14:00", "15:30", "PRODUCT", "spk-1"));
        tools = new AgendaTools(catalog,
                new FakeRegistrations()
                        .withSeats("ses-1", "Streams deep dive", 12)
                        .withSeats("ses-3", "Afternoon workshop", 4),
                new ClashDetector());
    }

    private AgendaBuilder agentDrivenBy(ScriptedChatModel model) {
        return AiServices.builder(AgendaBuilder.class)
                .chatModel(model)
                .tools(tools)
                .maxToolCallingRoundTrips(12)
                .build();
    }

    @Test
    void runsTheToolsTheModelAsksForAndReturnsAStructuredAgenda() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenCallsTool("searchSessions",
                        "{\"track\":\"PLATFORM\",\"day\":\"2026-09-14\",\"keyword\":\"streams\"}")
                .thenCallsTool("getSessionAvailability", "{\"sessionId\":\"ses-1\"}")
                .thenCallsTool("detectTimeClashes", "{\"sessionIds\":[\"ses-1\",\"ses-3\"]}")
                .thenAnswers("""
                        {
                          "items": [
                            {"sessionId": "ses-1", "rationale": "Core streams content, and it has seats."}
                          ],
                          "summary": "One morning session on stream processing."
                        }
                        """);

        ProposedAgenda agenda = agentDrivenBy(model).buildAgenda("att-1042", "streams and observability");

        assertThat(agenda.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.sessionId()).isEqualTo("ses-1");
                    assertThat(item.rationale()).contains("streams");
                });
        assertThat(agenda.summary()).isEqualTo("One morning session on stream processing.");
    }

    @Test
    void passesTheModelsToolArgumentsThroughToTheCatalogue() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenCallsTool("searchSessions",
                        "{\"track\":\"PLATFORM\",\"day\":\"2026-09-14\",\"speaker\":\"spk-1\",\"keyword\":\"streams\"}")
                .thenAnswers("{\"items\":[],\"summary\":\"Nothing suitable.\"}");

        agentDrivenBy(model).buildAgenda("att-1042", "streams");

        assertThat(catalog.queries()).containsExactly(
                new FakeCatalog.Query("PLATFORM", LocalDate.parse("2026-09-14"), "spk-1", "streams"));
    }

    @Test
    void takesAsManyTurnsAsTheModelNeeds() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenCallsTool("searchSessions", "{\"track\":\"PLATFORM\"}")
                .thenCallsTool("searchSessions", "{\"track\":\"PRODUCT\"}")
                .thenCallsTool("getAttendeeRegistrations", "{\"attendeeId\":\"att-1042\"}")
                .thenCallsTool("detectTimeClashes", "{\"sessionIds\":[\"ses-1\",\"ses-3\"]}")
                .thenAnswers("""
                        {"items":[{"sessionId":"ses-1","rationale":"a"},
                                  {"sessionId":"ses-3","rationale":"b"}],
                         "summary":"Two sessions."}
                        """);

        ProposedAgenda agenda = agentDrivenBy(model).buildAgenda("att-1042", "anything");

        assertThat(agenda.items()).hasSize(2);
        assertThat(model.turns()).isEqualTo(5);
    }

    @Test
    void theToolsTheModelRanAreVisibleInTheDossierAfterwards() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenCallsTool("searchSessions", "{\"track\":\"PLATFORM\"}")
                .thenAnswers("{\"items\":[],\"summary\":\"none\"}");

        agentDrivenBy(model).buildAgenda("att-1042", "anything");

        assertThat(tools.dossier().keySet()).containsExactlyInAnyOrder("ses-1", "ses-2");
    }

    @Test
    void offersEveryToolToTheModel() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenAnswers("{\"items\":[],\"summary\":\"none\"}");

        agentDrivenBy(model).buildAgenda("att-1042", "anything");

        assertThat(model.requests().getFirst().toolSpecifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrder("searchSessions", "getSessionAvailability",
                        "getAttendeeRegistrations", "detectTimeClashes");
    }
}
