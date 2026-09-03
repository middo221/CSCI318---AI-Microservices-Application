package au.edu.uow.csci318.conference.registration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point for the Registration bounded context. */
@SpringBootApplication
@EnableScheduling
public class RegistrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegistrationApplication.class, args);
    }
}
