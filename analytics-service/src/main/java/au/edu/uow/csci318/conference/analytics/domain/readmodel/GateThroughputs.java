package au.edu.uow.csci318.conference.analytics.domain.readmodel;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;

import java.util.List;
import java.util.Optional;

/**
 * Reads the RT2 per-gate windows (FR-16). Throws {@link ReadModelUnavailableException} while the
 * store cannot be queried.
 */
public interface GateThroughputs {

    /** The gate's most recent one-minute window - not its busiest. Empty if it has admitted nobody. */
    Optional<GateThroughput> latest(String gateId);

    /** One entry per gate: each gate's most recent window. */
    List<GateThroughput> latestForEveryGate();
}
