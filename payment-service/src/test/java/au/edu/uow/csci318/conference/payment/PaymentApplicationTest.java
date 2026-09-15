package au.edu.uow.csci318.conference.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @EnableTestBinder} keeps the context off the network. Without it the Kafka binder is the
 * only one on the classpath, and the test spends its run retrying a broker that is not there.
 */
@SpringBootTest
@EnableTestBinder
class PaymentApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContextLoads() {
        assertThat(context).isNotNull();
    }
}
