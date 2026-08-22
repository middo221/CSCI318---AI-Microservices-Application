package au.edu.uow.csci318.conference.concierge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the Attendee assistance bounded context. */
@SpringBootApplication
public class ConciergeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConciergeApplication.class, args);
    }
}
