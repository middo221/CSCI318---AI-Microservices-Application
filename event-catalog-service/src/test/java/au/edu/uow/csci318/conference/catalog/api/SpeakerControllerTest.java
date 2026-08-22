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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class SpeakerControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static final String VALID = """
            {
              "name": "Dr Chen",
              "bio": "Works on observability",
              "organisation": "University of Wollongong"
            }
            """;

    @Test
    void createsASpeaker() throws Exception {
        mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Dr Chen"))
                .andExpect(jsonPath("$.organisation").value("University of Wollongong"));
    }

    @Test
    void readsBackACreatedSpeaker() throws Exception {
        String body = mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc().perform(get("/api/v1/speakers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Dr Chen"));
    }

    @Test
    void rejectsABlankName() throws Exception {
        mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"Dr Chen\"", "\"  \"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsNotFoundForAnUnknownSpeaker() throws Exception {
        mockMvc().perform(get("/api/v1/speakers/{id}", "no-such-speaker"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPEAKER_NOT_FOUND"));
    }
}
