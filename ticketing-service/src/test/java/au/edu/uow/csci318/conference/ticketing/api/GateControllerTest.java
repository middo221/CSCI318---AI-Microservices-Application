package au.edu.uow.csci318.conference.ticketing.api;

import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import au.edu.uow.csci318.conference.ticketing.application.command.IssueTicketsCommand;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The gate surface (FR-13), and the pair of timestamps FR-16 is built on. */
@SpringBootTest
@EnableTestBinder
class GateControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TicketApplicationService tickets;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Ticket givenATicket(String registrationId) {
        return tickets.issue(new IssueTicketsCommand(registrationId, "att-1042", "Dana Okafor",
                "dana@example.com", List.of("ses-1"))).get(0);
    }

    private static String checkInBody(String code, Instant queuedAt) {
        return """
                { "ticketCode": "%s", "queuedAt": "%s" }
                """.formatted(code, queuedAt);
    }

    @Test
    void checksAnAttendeeInAndReportsTheWait() throws Exception {
        Ticket ticket = givenATicket("gate-ok");
        Instant queued = Instant.now().minusSeconds(120);

        String body = mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(ticket.getTicketCode().value(), queued)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkInId").exists())
                .andExpect(jsonPath("$.ticketCode").value(ticket.getTicketCode().value()))
                .andExpect(jsonPath("$.gateId").value("north-1"))
                .andExpect(jsonPath("$.sessionId").value("ses-1"))
                .andExpect(jsonPath("$.queuedAt").exists())
                .andExpect(jsonPath("$.checkedInAt").exists())
                .andReturn().getResponse().getContentAsString();

        // The wait is derived from the two timestamps rather than stored, so the three values
        // cannot disagree. This is the whole input to the FR-16 congestion window.
        assertThat(objectMapper.readTree(body).get("waitMillis").asLong())
                .isGreaterThanOrEqualTo(120_000L);
    }

    @Test
    void returns404ForAnUnknownTicketCode() throws Exception {
        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("TKT-NOSUCHCODE", Instant.now().minusSeconds(60))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    /** Specification section 5.1: a ticket may be checked in at most once. */
    @Test
    void refusesASecondCheckIn() throws Exception {
        Ticket ticket = givenATicket("gate-twice");
        String code = ticket.getTicketCode().value();
        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(code, Instant.now().minusSeconds(120))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(code, Instant.now().minusSeconds(60))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
    }

    @Test
    void refusesToCheckInAVoidTicket() throws Exception {
        Ticket ticket = givenATicket("gate-void");
        tickets.voidTicketsFor("gate-void", "REFUND_ISSUED");

        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(ticket.getTicketCode().value(), Instant.now().minusSeconds(60))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_VOID"));
    }

    /** queuedAt is required: the gate congestion stream derives its wait times from it. */
    @Test
    void rejectsACheckInWithNoQueuedAt() throws Exception {
        Ticket ticket = givenATicket("gate-noqueue");

        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"ticketCode\": \"%s\" }".formatted(ticket.getTicketCode().value())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsACheckInWithNoTicketCode() throws Exception {
        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"queuedAt\": \"%s\" }".formatted(Instant.now().minusSeconds(60))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** A gate clock running ahead of the server would otherwise record a negative wait. */
    @Test
    void rejectsAQueuedAtInTheFuture() throws Exception {
        Ticket ticket = givenATicket("gate-future");

        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(ticket.getTicketCode().value(), Instant.now().plusSeconds(600))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
