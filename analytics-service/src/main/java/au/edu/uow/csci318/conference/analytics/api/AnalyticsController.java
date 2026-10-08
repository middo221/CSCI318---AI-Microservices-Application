package au.edu.uow.csci318.conference.analytics.api;

import au.edu.uow.csci318.conference.analytics.api.dto.FillRateResponse;
import au.edu.uow.csci318.conference.analytics.api.dto.FunnelResponse;
import au.edu.uow.csci318.conference.analytics.api.dto.GateThroughputResponse;
import au.edu.uow.csci318.conference.analytics.application.AnalyticsQueryService;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The organiser's live view of the event (specification section 4, analytics). Every answer comes
 * from a Kafka Streams state store by interactive query — this service has no database.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsQueryService queries;

    public AnalyticsController(AnalyticsQueryService queries) {
        this.queries = queries;
    }

    /** FR-14. */
    @GetMapping("/sessions/{sessionId}/fill-rate")
    public FillRateResponse fillRate(@PathVariable String sessionId) {
        return FillRateResponse.from(queries.fillRate(sessionId));
    }

    /** FR-15. {@code threshold} defaults to {@code conference.analytics.capacity-alert-threshold}. */
    @GetMapping("/sessions/at-risk")
    public List<FillRateResponse> sessionsAtRisk(@RequestParam(required = false) Double threshold) {
        return queries.sessionsAtRisk(threshold).stream().map(FillRateResponse::from).toList();
    }

    /** FR-16. */
    @GetMapping("/gates/{gateId}/throughput")
    public GateThroughputResponse gateThroughput(@PathVariable String gateId) {
        GateThroughput throughput = queries.gateThroughput(gateId);
        return GateThroughputResponse.from(throughput, queries.isCongested(throughput));
    }

    /** FR-16. */
    @GetMapping("/gates/congested")
    public List<GateThroughputResponse> congestedGates() {
        return queries.congestedGates().stream()
                .map(throughput -> GateThroughputResponse.from(throughput, true))
                .toList();
    }

    /** Supporting projection, design section 7.3. */
    @GetMapping("/funnel")
    public FunnelResponse funnel() {
        return FunnelResponse.from(queries.funnel());
    }
}
