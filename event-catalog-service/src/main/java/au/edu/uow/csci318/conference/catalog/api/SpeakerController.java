package au.edu.uow.csci318.conference.catalog.api;

import au.edu.uow.csci318.conference.catalog.api.dto.CreateSpeakerRequest;
import au.edu.uow.csci318.conference.catalog.api.dto.SpeakerResponse;
import au.edu.uow.csci318.conference.catalog.application.SpeakerApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for speakers. Supports FR-02 and FR-05. */
@RestController
@RequestMapping("/api/v1/speakers")
public class SpeakerController {

    private final SpeakerApplicationService speakers;

    public SpeakerController(SpeakerApplicationService speakers) {
        this.speakers = speakers;
    }

    @PostMapping
    public ResponseEntity<SpeakerResponse> create(@Valid @RequestBody CreateSpeakerRequest request) {
        SpeakerResponse body = SpeakerResponse.from(speakers.create(request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public SpeakerResponse getById(@PathVariable String id) {
        return SpeakerResponse.from(speakers.getById(id));
    }
}
