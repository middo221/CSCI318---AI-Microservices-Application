package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.RefundDecision;

/** What the policy would return today for one order — read-only, nothing decided. */
public record RefundQuote(PaymentOrder order, RefundDecision decision) {
}
