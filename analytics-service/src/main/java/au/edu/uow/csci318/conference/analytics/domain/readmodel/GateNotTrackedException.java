package au.edu.uow.csci318.conference.analytics.domain.readmodel;

/** No check-ins have been recorded at a gate. Maps to {@code 404 GATE_NOT_TRACKED}. */
public class GateNotTrackedException extends RuntimeException {

    public GateNotTrackedException(String gateId) {
        super("no check-ins recorded at gate " + gateId);
    }
}
