package au.edu.uow.csci318.conference.payment.api;

import au.edu.uow.csci318.conference.payment.api.dto.PaymentOrderResponse;
import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Billing read surface (FR-07).
 *
 * <p>Read-only by design. There is no "pay" endpoint: payment is started by
 * {@code registration-requested}, carrying the card token the attendee gave when registering. These
 * endpoints exist so an attendee — and, in M4, the support agent — can see what happened.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentApplicationService payments;

    public PaymentController(PaymentApplicationService payments) {
        this.payments = payments;
    }

    @GetMapping("/payment-orders/{paymentOrderId}")
    public PaymentOrderResponse byId(@PathVariable String paymentOrderId) {
        return PaymentOrderResponse.from(payments.findById(paymentOrderId));
    }

    @GetMapping("/registrations/{registrationId}/payment-order")
    public PaymentOrderResponse byRegistration(@PathVariable String registrationId) {
        return PaymentOrderResponse.from(payments.findByRegistrationId(registrationId));
    }
}
