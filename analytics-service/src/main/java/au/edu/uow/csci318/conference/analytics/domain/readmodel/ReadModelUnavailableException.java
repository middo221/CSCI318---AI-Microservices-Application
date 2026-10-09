package au.edu.uow.csci318.conference.analytics.domain.readmodel;

/**
 * A read model exists but cannot be queried yet - its stream is starting, restoring from its
 * changelog, rebalancing, or cannot reach the broker. Maps to {@code 503 STORE_REBUILDING}: the
 * caller did nothing wrong and should ask again shortly.
 */
public class ReadModelUnavailableException extends RuntimeException {

    public ReadModelUnavailableException(String readModel, Throwable cause) {
        super("analytics read model '" + readModel + "' is not queryable yet - its stream is starting"
                + " or restoring state; retry shortly", cause);
    }
}
