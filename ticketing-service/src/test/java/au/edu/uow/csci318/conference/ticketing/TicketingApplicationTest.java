package au.edu.uow.csci318.conference.ticketing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @EnableTestBinder} is not optional here. Once this module gained a consumer binding, a
 * plain {@code @SpringBootTest} starts the <em>real</em> Kafka binder, whose admin client then
 * spends sixty seconds failing to reach {@code localhost:9092}. The standing constraint is that
 * {@code ./mvnw clean test} passes with no Docker container running and no network access, so
 * every full-context test in this module substitutes the test binder.
 */
@SpringBootTest
@EnableTestBinder
class TicketingApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContextLoads() {
        assertThat(context).isNotNull();
    }
}
