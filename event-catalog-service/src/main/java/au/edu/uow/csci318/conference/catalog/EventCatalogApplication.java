package au.edu.uow.csci318.conference.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Programme bounded context.
 *
 * <p>Owns events, sessions, speakers and venues, and is the only service permitted to
 * write to the {@code catalogdb} schema.
 */
@SpringBootApplication
public class EventCatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventCatalogApplication.class, args);
    }
}
