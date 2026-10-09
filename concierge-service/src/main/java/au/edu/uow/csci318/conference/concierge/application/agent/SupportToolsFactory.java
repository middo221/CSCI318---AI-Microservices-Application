package au.edu.uow.csci318.conference.concierge.application.agent;

import au.edu.uow.csci318.conference.concierge.application.port.EscalationStore;
import au.edu.uow.csci318.conference.concierge.application.port.PaymentPort;
import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.application.port.TicketingPort;
import au.edu.uow.csci318.conference.concierge.domain.service.RefundGuard;
import org.springframework.stereotype.Component;

/**
 * Mints a fresh {@link SupportTools} for each support conversation, bound to one attendee.
 *
 * <p>This is where the attendee is fixed (plan refinement 1). The id passed here comes from the
 * HTTP request, before any model is involved, and nothing afterwards can change it.
 */
@Component
public class SupportToolsFactory {

    private final RegistrationPort registrations;
    private final PaymentPort payments;
    private final TicketingPort ticketing;
    private final RefundGuard refundGuard;
    private final EscalationStore escalationStore;

    public SupportToolsFactory(RegistrationPort registrations, PaymentPort payments, TicketingPort ticketing,
                               RefundGuard refundGuard, EscalationStore escalationStore) {
        this.registrations = registrations;
        this.payments = payments;
        this.ticketing = ticketing;
        this.refundGuard = refundGuard;
        this.escalationStore = escalationStore;
    }

    public SupportTools create(String attendeeId, String attendeeMessage) {
        return new SupportTools(attendeeId, attendeeMessage, registrations, payments, ticketing,
                refundGuard, escalationStore);
    }
}
