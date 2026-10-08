package au.edu.uow.csci318.conference.payment.api;

import au.edu.uow.csci318.conference.payment.api.dto.IssueRefundRequest;
import au.edu.uow.csci318.conference.payment.api.dto.RefundQuoteResponse;
import au.edu.uow.csci318.conference.payment.api.dto.RefundResponse;
import au.edu.uow.csci318.conference.payment.application.RefundApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Billing's refund surface (FR-11).
 *
 * <p>There is deliberately no endpoint here for an attendee to ask for a refund: that request
 * belongs to the Registration context, which owns the registration being given up, and reaches
 * Billing as {@code refund-requested}. These two endpoints are the read the support agent needs
 * before acting, and the override an organiser needs to resolve an escalation.
 */
@RestController
@RequestMapping("/api/v1")
public class RefundController {

    private final RefundApplicationService refunds;

    public RefundController(RefundApplicationService refunds) {
        this.refunds = refunds;
    }

    /** A blank id is treated as a missing one, so both answer the same {@code 400}. */
    @GetMapping("/refund-policy/quote")
    public RefundQuoteResponse quote(@RequestParam(required = false) String registrationId)
            throws MissingServletRequestParameterException {
        if (registrationId == null || registrationId.isBlank()) {
            throw new MissingServletRequestParameterException("registrationId", "String");
        }
        return RefundQuoteResponse.from(refunds.quote(registrationId.trim()));
    }

    @PostMapping("/payment-orders/{paymentOrderId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundResponse issue(@PathVariable String paymentOrderId,
                                @Valid @RequestBody IssueRefundRequest request) {
        return RefundResponse.from(refunds.issueByOrganiser(request.toCommand(paymentOrderId)));
    }
}
