package au.edu.uow.csci318.conference.ticketing.api;

import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import au.edu.uow.csci318.conference.ticketing.application.command.CheckInCommand;
import au.edu.uow.csci318.conference.ticketing.application.command.IssueTicketsCommand;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ticket surface: the two reads FR-18's support agent depends on, and the transfer of FR-12.
 *
 * <p>{@code ticketdb} outlives each test method, so every test uses its own registration id.
 */
@SpringBootTest
@EnableTestBinder
class TicketControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TicketApplicationService tickets;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Ticket givenATicket(String registrationId) {
        return tickets.issue(new IssueTicketsCommand(registrationId, "att-1042", "Dana Okafor",
                "dana@example.com", List.of("ses-1"))).get(0);
    }

    private static String transferBody(String name, String email) {
        return """
                { "toHolderName": "%s", "toHolderEmail": "%s" }
                """.formatted(name, email);
    }

    @Test
    void readsATicketByItsCode() throws Exception {
        Ticket ticket = givenATicket("api-read");

        mockMvc.perform(get("/api/v1/tickets/{code}", ticket.getTicketCode().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketCode").value(ticket.getTicketCode().value()))
                .andExpect(jsonPath("$.registrationId").value("api-read"))
                .andExpect(jsonPath("$.sessionId").value("ses-1"))
                .andExpect(jsonPath("$.attendeeId").value("att-1042"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.holderName").value("Dana Okafor"))
                .andExpect(jsonPath("$.holderEmail").value("dana@example.com"))
                .andExpect(jsonPath("$.issuedAt").exists())
                .andExpect(jsonPath("$.checkIns.length()").value(0))
                .andExpect(jsonPath("$.transfers.length()").value(0));
    }

    @Test
    void returns404ForAnUnknownCode() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/{code}", "TKT-NOSUCHCODE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void listsEveryTicketOfARegistration() throws Exception {
        tickets.issue(new IssueTicketsCommand("api-list", "att-1042", "Dana Okafor",
                "dana@example.com", List.of("ses-1", "ses-2")));

        mockMvc.perform(get("/api/v1/registrations/{id}/tickets", "api-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].registrationId").value("api-list"));
    }

    @Test
    void returnsAnEmptyListForARegistrationWithNoTickets() throws Exception {
        mockMvc.perform(get("/api/v1/registrations/{id}/tickets", "api-none"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** FR-12. */
    @Test
    void transfersATicketAndRecordsTheHistory() throws Exception {
        Ticket ticket = givenATicket("api-xfer");

        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", ticket.getTicketCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("Sam Patel", "sam@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderName").value("Sam Patel"))
                .andExpect(jsonPath("$.holderEmail").value("sam@example.com"))
                .andExpect(jsonPath("$.status").value("TRANSFERRED"))
                .andExpect(jsonPath("$.transfers.length()").value(1))
                .andExpect(jsonPath("$.transfers[0].fromHolderEmail").value("dana@example.com"))
                .andExpect(jsonPath("$.transfers[0].toHolderEmail").value("sam@example.com"))
                .andExpect(jsonPath("$.transfers[0].transferredAt").exists());
    }

    @Test
    void returns404WhenTransferringAnUnknownTicket() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", "TKT-NOSUCHCODE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("Sam Patel", "sam@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void refusesToTransferAVoidTicket() throws Exception {
        Ticket ticket = givenATicket("api-xfer-void");
        tickets.voidTicketsFor("api-xfer-void", "REFUND_ISSUED");

        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", ticket.getTicketCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("Sam Patel", "sam@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_VOID"));
    }

    @Test
    void refusesToTransferAUsedTicket() throws Exception {
        Ticket ticket = givenATicket("api-xfer-used");
        tickets.checkIn(new CheckInCommand(ticket.getTicketCode().value(), "north-1",
                Instant.now().minusSeconds(120)));

        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", ticket.getTicketCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("Sam Patel", "sam@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
    }

    @Test
    void rejectsATransferWithABlankHolderName() throws Exception {
        Ticket ticket = givenATicket("api-xfer-blank");

        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", ticket.getTicketCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("  ", "sam@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsATransferToSomethingThatIsNotAnEmailAddress() throws Exception {
        Ticket ticket = givenATicket("api-xfer-bademail");

        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", ticket.getTicketCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("Sam Patel", "not-an-address")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
