package au.edu.uow.csci318.conference.analytics.application;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateNotTrackedException;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateThroughputs;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.RegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionFillRates;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionNotTrackedException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * The organiser's questions about the live event (FR-14, FR-15, FR-16), answered from the read
 * models. No transactions and no writes: this context only ever reads what the topologies built.
 *
 * <p>The thresholds an organiser tunes are applied here, not in the stores, so the same table can
 * answer "at or above 90%" and "at or above 50%" alike.
 */
@Service
public class AnalyticsQueryService {

    private final SessionFillRates fillRates;
    private final GateThroughputs gates;
    private final RegistrationFunnelCounts funnel;
    private final AlertThresholds thresholds;

    public AnalyticsQueryService(SessionFillRates fillRates, GateThroughputs gates,
                                 RegistrationFunnelCounts funnel, AlertThresholds thresholds) {
        this.fillRates = fillRates;
        this.gates = gates;
        this.funnel = funnel;
        this.thresholds = thresholds;
    }

    public FillRate fillRate(String sessionId) {
        return fillRates.find(sessionId).orElseThrow(() -> new SessionNotTrackedException(sessionId));
    }

    /**
     * Sessions at or above {@code threshold}, fullest first. {@code null} means the configured alert
     * threshold, so the default answer matches what FR-15 alerts on. There is no upper bound: a rate
     * above one is a session over capacity, which is worth being able to ask for.
     */
    public List<FillRate> sessionsAtRisk(Double threshold) {
        double floor = threshold == null ? thresholds.capacityAlertRate() : threshold;
        if (!Double.isFinite(floor) || floor < 0) {
            throw new IllegalArgumentException("threshold must be a number of zero or more, was " + threshold);
        }
        return fillRates.all().stream()
                .filter(rate -> rate.isAtOrAbove(floor))
                .sorted(Comparator.comparingDouble(FillRate::fillRate).reversed()
                        .thenComparing(FillRate::sessionId))
                .toList();
    }

    public GateThroughput gateThroughput(String gateId) {
        return gates.latest(gateId).orElseThrow(() -> new GateNotTrackedException(gateId));
    }

    /** Gates whose most recent minute is congested, longest average wait first. */
    public List<GateThroughput> congestedGates() {
        return gates.latestForEveryGate().stream()
                .filter(this::isCongested)
                .sorted(Comparator.comparingLong(GateThroughput::averageWaitMillis).reversed()
                        .thenComparing(GateThroughput::gateId))
                .toList();
    }

    public boolean isCongested(GateThroughput throughput) {
        return throughput.isCongested(thresholds.gateCongestionWait());
    }

    public RegistrationFunnel funnel() {
        return funnel.current();
    }
}
