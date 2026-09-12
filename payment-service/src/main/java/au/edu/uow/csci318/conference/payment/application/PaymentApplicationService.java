package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.infrastructure.persistence.PaymentOrderJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class PaymentApplicationService {

    private final PaymentOrderJpaRepository paymentOrders;

    public PaymentApplicationService(PaymentOrderJpaRepository paymentOrders) {
        this.paymentOrders = paymentOrders;
    }

    @Transactional
    public PaymentOrder createFromRegistration(RegistrationRequested event) {
        Optional<PaymentOrder> existing =
                paymentOrders.findByRegistrationId(event.registrationId());

        if (existing.isPresent()) {
            return existing.get();
        }

        PaymentOrder order = PaymentOrder.create(
                event.registrationId(),
                new Money(event.totalAmount(), event.currency()));

        return paymentOrders.save(order);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentOrder> findById(String id) {
        return paymentOrders.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentOrder> findByRegistrationId(String registrationId) {
        return paymentOrders.findByRegistrationId(registrationId);
    }
}