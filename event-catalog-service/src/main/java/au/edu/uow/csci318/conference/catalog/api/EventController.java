package au.edu.uow.csci318.conference.catalog.api;

import au.edu.uow.csci318.conference.catalog.api.dto.CreateEventRequest;
import au.edu.uow.csci318.conference.catalog.api.dto.CreateSessionRequest;
import au.edu.uow.csci318.conference.catalog.api.dto.EventResponse;
import au.edu.uow.csci318.conference.catalog.api.dto.SessionResponse;
import au.edu.uow.csci318.conference.catalog.application.EventApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for the event programme. Implements FR-01 and read-by-id. */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventApplicationService events;

    public EventController(EventApplicationService events) {
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<EventResponse> createDraft(@Valid @RequestBody CreateEventRequest request) {
        EventResponse body = EventResponse.from(events.createDraft(request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public EventResponse getById(@PathVariable String id) {
        return EventResponse.from(events.getById(id));
    }

    @PostMapping("/{id}/publish")
    public EventResponse publish(@PathVariable String id) {
        return EventResponse.from(events.publish(id));
    }

    @PostMapping("/{id}/sessions")
    public ResponseEntity<SessionResponse> addSession(@PathVariable String id,
                                                      @Valid @RequestBody CreateSessionRequest request) {
        SessionResponse body = SessionResponse.from(id, events.addSession(id, request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
