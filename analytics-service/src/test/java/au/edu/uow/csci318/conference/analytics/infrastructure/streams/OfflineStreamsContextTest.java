package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** The Kafka Streams binder is on the classpath and its interactive-query service is wired. */
@SpringBootTest
class OfflineStreamsContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresTheInteractiveQueryService() {
        assertThat(context.getBeansOfType(InteractiveQueryService.class)).hasSize(1);
    }
}
