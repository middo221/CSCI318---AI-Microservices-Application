package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistPlace;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Every read follows a flush and clear, or {@code findById} returns the cached instance unread. */
@DataJpaTest
@Import(JpaWaitlistRepository.class)
class JpaWaitlistRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");

    @Autowired
    private JpaWaitlistRepository waitlists;

    @Autowired
    private EntityManager entityManager;

    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void roundTripsAQueueInJoiningOrder() {
        Waitlist waitlist = Waitlist.forSession("ses-1");
        waitlist.join("wle-1", "att-1", NOW);
        waitlist.join("wle-2", "att-2", NOW.plusSeconds(1));
        waitlist.join("wle-3", "att-3", NOW.plusSeconds(2));
        waitlist.promote(1, NOW.plusSeconds(60));
        waitlists.save(waitlist);
        detach();

        assertThat(waitlists.findBySessionId("ses-1").orElseThrow().places())
                .extracting(WaitlistPlace::waitlistEntryId, WaitlistPlace::status, WaitlistPlace::position)
                .containsExactly(
                        tuple("wle-1", WaitlistStatus.PROMOTED, null),
                        tuple("wle-2", WaitlistStatus.WAITING, 1),
                        tuple("wle-3", WaitlistStatus.WAITING, 2));
    }

    @Test
    void addsToAQueueAlreadySaved() {
        Waitlist waitlist = Waitlist.forSession("ses-2");
        waitlist.join("wle-1", "att-1", NOW);
        waitlists.save(waitlist);
        detach();

        Waitlist loaded = waitlists.findBySessionId("ses-2").orElseThrow();
        loaded.join("wle-2", "att-2", NOW.plusSeconds(1));
        waitlists.save(loaded);
        detach();

        assertThat(waitlists.findBySessionId("ses-2").orElseThrow().places())
                .extracting(WaitlistPlace::attendeeId)
                .containsExactly("att-1", "att-2");
    }

    @Test
    void returnsEmptyForASessionNobodyHasQueuedFor() {
        assertThat(waitlists.findBySessionId("ses-nobody")).isEmpty();
    }
}
