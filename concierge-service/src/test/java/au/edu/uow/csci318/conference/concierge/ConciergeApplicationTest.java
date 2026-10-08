package au.edu.uow.csci318.conference.concierge;

import au.edu.uow.csci318.conference.concierge.domain.service.RefundGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConciergeApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContextLoads() {
        assertThat(context).isNotNull();
    }

    /** The limit is configuration, and it is the configured value the guard enforces. */
    @Test
    void bindsTheRefundLimitFromConfiguration() {
        RefundGuard guard = context.getBean(RefundGuard.class);

        assertThat(guard.autoApproveThreshold()).isEqualByComparingTo("200.00");
        assertThat(guard.currency()).isEqualTo("AUD");
    }
}
