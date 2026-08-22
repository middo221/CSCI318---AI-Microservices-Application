package au.edu.uow.csci318.conference.catalog.api;

import au.edu.uow.csci318.conference.catalog.api.dto.CreateEventRequest;
import au.edu.uow.csci318.conference.catalog.api.dto.CreateSessionRequest;
import au.edu.uow.csci318.conference.catalog.api.dto.EventResponse;
import au.edu.uow.csci318.conference.catalog.api.dto.SessionResponse;
import au.edu.uow.csci318.conference.catalog.api.dto.UpdateCapacityRequest;
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.application.EventApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /**
     * Lists events (FR-05). {@code status} is optional; when absent, every event is returned.
     *
     * <p>An unrecognised status is a client error, so it is converted here rather than allowed to
     * surface as a Spring type-mismatch 400 with an unhelpful body.
     */
    @GetMapping
    public List<EventResponse> list(@RequestParam(required = false) String status) {
        EventStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = EventStatus.valueOf(status.trim().toUpperCase());
            }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unknown event status " + status);
            }
        }
        return events.list(filter).stream().map(EventResponse::from).toList();
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

    @PutMapping("/{id}/sessions/{sessionId}/capacity")
    public SessionResponse changeCapacity(@PathVariable String id,
                                          @PathVariable String sessionId,
                                          @Valid @RequestBody UpdateCapacityRequest request) {
        return SessionResponse.from(id,
                events.changeSessionCapacity(id, sessionId, SeatCapacity.of(request.seatCapacity())));
    }
}
