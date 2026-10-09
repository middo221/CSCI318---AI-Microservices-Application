package au.edu.uow.csci318.conference.concierge.infrastructure.escalation;

import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.FIXED_CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEscalationStoreTest {

    private final InMemoryEscalationStore store = new InMemoryEscalationStore(FIXED_CLOCK);

    @Test
    void assignsAnIdAndTheClocksTime() {
        Escalation escalation = store.open("att-1042", "reg-1", "Above the limit", "Wants 447.00 back",
                "Please refund me.", Escalation.RaisedBy.ASSISTANT);

        assertThat(escalation.escalationId()).isNotBlank();
        assertThat(escalation.createdAt()).isEqualTo(Instant.parse("2026-10-09T02:00:00Z"));
        assertThat(escalation.attendeeId()).isEqualTo("att-1042");
        assertThat(escalation.registrationId()).isEqualTo("reg-1");
        assertThat(escalation.attendeeMessage()).isEqualTo("Please refund me.");
        assertThat(escalation.raisedBy()).isEqualTo(Escalation.RaisedBy.ASSISTANT);
    }

    @Test
    void listsNewestFirst() {
        Escalation first = store.open("att-1042", null, "first", "", "one", Escalation.RaisedBy.ASSISTANT);
        Escalation second = store.open("att-2000", null, "second", "", "two", Escalation.RaisedBy.SYSTEM);

        assertThat(store.all()).containsExactly(second, first);
    }

    @Test
    void handsOutASnapshotRatherThanItsOwnList() {
        List<Escalation> before = store.all();
        store.open("att-1042", null, "later", "", "three", Escalation.RaisedBy.ASSISTANT);

        assertThat(before).isEmpty();
        assertThat(store.all()).hasSize(1);
    }
}
