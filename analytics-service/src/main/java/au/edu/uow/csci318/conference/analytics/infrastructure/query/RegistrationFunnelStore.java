package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.FunnelStage;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.RegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.StoreNames;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@link RegistrationFunnelCounts} answered from the {@code registration-funnel} store, one row per
 * stage. A stage that has seen nothing has no row and counts as zero.
 */
@Component
public class RegistrationFunnelStore implements RegistrationFunnelCounts {

    private final StateStoreLookup stores;

    public RegistrationFunnelStore(StateStoreLookup stores) {
        this.stores = stores;
    }

    @Override
    public RegistrationFunnel current() {
        return StoreReads.guarded(StoreNames.REGISTRATION_FUNNEL, () -> {
            ReadOnlyKeyValueStore<String, Long> store = stores.keyValueStore(StoreNames.REGISTRATION_FUNNEL);
            Map<FunnelStage, Long> counts = new EnumMap<>(FunnelStage.class);
            for (FunnelStage stage : FunnelStage.values()) {
                Long count = store.get(stage.name());
                if (count != null) {
                    counts.put(stage, count);
                }
            }
            return RegistrationFunnel.of(counts);
        });
    }
}
