package au.edu.uow.csci318.conference.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EventCatalogApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getApplicationName()).isNotNull();
    }
}
