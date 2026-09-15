package au.edu.uow.csci318.conference.payment.domain.gateway;

import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;

/**
 * Outbound port to whatever actually moves the money.
 *
 * <p>Declared in the domain layer, implemented in infrastructure by
 * {@code SimulatedPaymentGateway}. The order depends on this interface, so replacing the simulation
 * with a real provider would be an infrastructure change only — and the domain tests can hand the
 * order a lambda that answers however the test needs.
 */
@FunctionalInterface
public interface PaymentGateway {

    /**
     * Attempts one charge. Never throws for a business outcome: a decline or a timeout is an
     * answer, reported in the result, not an exception.
     */
    ChargeResult charge(String paymentOrderId, Money amount, CardToken cardToken);
}
