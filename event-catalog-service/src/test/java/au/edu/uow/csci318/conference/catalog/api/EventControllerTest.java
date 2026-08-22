package au.edu.uow.csci318.conference.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class EventControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static String validRequestJson() {
        return """
                {
                  "name": "SoftEng Summit",
                  "description": "Three days of practice",
                  "venue": {
                    "name": "Innovation Campus",
                    "addressLine": "Squires Way",
                    "city": "North Wollongong"
                  },
                  "startDate": "2026-09-14",
                  "endDate": "2026-09-16"
                }
                """;
    }

    @Test
    void createsADraftEvent() throws Exception {
        mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("SoftEng Summit"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.venue.city").value("North Wollongong"))
                .andExpect(jsonPath("$.startDate").value("2026-09-14"))
                .andExpect(jsonPath("$.endDate").value("2026-09-16"));
    }

    @Test
    void readsBackACreatedEvent() throws Exception {
        String body = mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc().perform(get("/api/v1/events/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("SoftEng Summit"));
    }

    @Test
    void rejectsABlankName() throws Exception {
        String invalid = validRequestJson().replace("\"SoftEng Summit\"", "\"   \"");

        mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("name")));
    }

    @Test
    void rejectsAnEndDateBeforeItsStartDate() throws Exception {
        String invalid = validRequestJson().replace("\"2026-09-16\"", "\"2026-09-01\"");

        mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsNotFoundForAnUnknownEvent() throws Exception {
        mockMvc().perform(get("/api/v1/events/{id}", "no-such-event"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }
}
