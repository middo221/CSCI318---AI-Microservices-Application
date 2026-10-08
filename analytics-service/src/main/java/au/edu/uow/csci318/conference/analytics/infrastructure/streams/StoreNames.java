package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

/**
 * State store names, shared by the topologies that write them and the adapters that query them.
 * Interactive queries find a store by name alone, so one constant on each side is what keeps a
 * renamed store from turning every query into a 503.
 */
public final class StoreNames {

    /** RT1: running total of seat deltas per session. */
    public static final String RESERVED_SEATS = "reserved-seats";

    /** RT1: the fill-rate table served by FR-14's endpoints. */
    public static final String SESSION_FILL_RATES = "session-fill-rates";

    /** RT1: sessions that have already alerted, so FR-15 alerts once. */
    public static final String CAPACITY_ALERTS_SENT = "capacity-alerts-sent";

    /** RT2: one-minute windows per gate, served by FR-16's endpoints. */
    public static final String GATE_THROUGHPUT = "gate-throughput";

    /** RT2: the last window each gate alerted for. */
    public static final String GATE_ALERTS_SENT = "gate-alerts-sent";

    /** Funnel: one count per stage. */
    public static final String REGISTRATION_FUNNEL = "registration-funnel";

    private StoreNames() {
    }
}
