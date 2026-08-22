package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class SessionAvailabilityControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SessionViewRepository views;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void reportsAvailabilityForACompleteReplica() throws Exception {
        SessionView view = SessionView.forSession("ses-100");
        view.describe("evt-1", "Observability in practice",
                new TimeSlot(LocalDateTime.parse("2026-09-14T09:00"),
                        LocalDateTime.parse("2026-09-14T10:30")),
                Money.aud("49.50"));
        view.setCapacity(120);
        views.save(view);

        mockMvc().perform(get("/api/v1/sessions/{id}/availability", "ses-100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("ses-100"))
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.title").value("Observability in practice"))
                .andExpect(jsonPath("$.capacity").value(120))
                .andExpect(jsonPath("$.reservedSeats").value(0))
                .andExpect(jsonPath("$.availableSeats").value(120));
    }

    @Test
    void returnsNotFoundForASessionItHasNeverHeardOf() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/{id}/availability", "no-such-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void returnsNotFoundForAReplicaThatIsStillIncomplete() throws Exception {
        SessionView capacityOnly = SessionView.forSession("ses-101");
        capacityOnly.setCapacity(80);
        views.save(capacityOnly);

        mockMvc().perform(get("/api/v1/sessions/{id}/availability", "ses-101"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }
}
