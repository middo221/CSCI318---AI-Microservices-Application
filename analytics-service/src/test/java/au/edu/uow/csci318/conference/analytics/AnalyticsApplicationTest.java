package au.edu.uow.csci318.conference.analytics;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AnalyticsApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AlertThresholds thresholds;

    @Test
    void applicationContextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void bindsTheDemonstrationThresholdsFromApplicationYml() {
        assertThat(thresholds).isEqualTo(new AlertThresholds(0.9, Duration.ofMinutes(2)));
    }
}
