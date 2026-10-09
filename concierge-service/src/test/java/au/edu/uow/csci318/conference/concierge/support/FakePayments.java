package au.edu.uow.csci318.conference.concierge.support;

import au.edu.uow.csci318.conference.concierge.application.port.PaymentPort;
import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundQuote;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory refund quotes. A registration with no quote answers as payment does for a free one. */
public class FakePayments implements PaymentPort {

    private final Map<String, UpstreamResult<RefundQuote>> answers = new HashMap<>();
    private final List<String> quoteRequests = new ArrayList<>();
    private boolean unavailable;

    public FakePayments quoting(RefundQuote quote) {
        answers.put(quote.registrationId(), new UpstreamResult.Ok<>(quote));
        return this;
    }

    /** Answer a quote request for {@code registrationId} with {@code quote}, whatever registration it names. */
    public FakePayments quotingFor(String registrationId, RefundQuote quote) {
        answers.put(registrationId, new UpstreamResult.Ok<>(quote));
        return this;
    }

    public FakePayments rejecting(String registrationId, String code) {
        answers.put(registrationId, new UpstreamResult.Rejected<>(code, "refused by the fake"));
        return this;
    }

    public FakePayments unavailable() {
        unavailable = true;
        return this;
    }

    public List<String> quoteRequests() {
        return List.copyOf(quoteRequests);
    }

    public void clearCalls() {
        quoteRequests.clear();
    }

    @Override
    public UpstreamResult<RefundQuote> quoteRefund(String registrationId) {
        quoteRequests.add(registrationId);
        if (unavailable) {
            return new UpstreamResult.Unavailable<>("payment-service is down");
        }
        return answers.getOrDefault(registrationId, new UpstreamResult.Rejected<>(
                "PAYMENT_ORDER_NOT_FOUND", "no payment order for " + registrationId));
    }
}
