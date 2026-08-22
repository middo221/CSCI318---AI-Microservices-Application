package au.edu.uow.csci318.conference.catalog.api;

import au.edu.uow.csci318.conference.catalog.api.dto.SessionResponse;
import au.edu.uow.csci318.conference.catalog.application.CatalogSearchService;
import au.edu.uow.csci318.conference.catalog.application.command.SessionSearchQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Catalogue search across the published programme (FR-05).
 *
 * <p>Mapped at {@code /api/v1} rather than under {@code /api/v1/events}, because a session search
 * spans every event and is not a sub-resource of any one of them.
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

    private final CatalogSearchService search;

    public SessionController(CatalogSearchService search) {
        this.search = search;
    }

    @GetMapping("/sessions/search")
    public List<SessionResponse> search(
            @RequestParam(required = false) String track,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String speaker,
            @RequestParam(required = false) String q) {

        return search.search(new SessionSearchQuery(track, day, speaker, q)).stream()
                .map(hit -> SessionResponse.from(hit.eventId(), hit.session()))
                .toList();
    }
}
