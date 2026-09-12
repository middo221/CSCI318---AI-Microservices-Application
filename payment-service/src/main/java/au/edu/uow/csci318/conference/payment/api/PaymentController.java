package au.edu.uow.csci318.conference.payment.api;

import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentApplicationService payments;

    public PaymentController(PaymentApplicationService payments) {
        this.payments = payments;
    }

    @GetMapping("/payment-orders/{id}")
    public ResponseEntity<PaymentOrder> getPaymentOrder(@PathVariable String id) {
        return payments.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/registrations/{registrationId}/payment-order")
    public ResponseEntity<PaymentOrder> getPaymentOrderByRegistration(
            @PathVariable String registrationId) {

        return payments.findByRegistrationId(registrationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}