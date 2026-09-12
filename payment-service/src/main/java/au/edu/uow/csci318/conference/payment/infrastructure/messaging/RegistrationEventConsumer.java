package au.edu.uow.csci318.conference.payment.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@Configuration
public class RegistrationEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(RegistrationEventConsumer.class);

    private final PaymentApplicationService payments;

    public RegistrationEventConsumer(PaymentApplicationService payments) {
        this.payments = payments;
    }

    @Bean
    public Consumer<Message<RegistrationRequested>> registrationRequested() {
        return message -> {
            RegistrationRequested event = message.getPayload();

            PaymentOrder order = payments.createFromRegistration(event);

            log.info(
                    "payment order {} created for registration {} amount={} {}",
                    order.getId(),
                    order.getRegistrationId(),
                    order.getAmount().amount(),
                    order.getAmount().currency());
        };
    }
}