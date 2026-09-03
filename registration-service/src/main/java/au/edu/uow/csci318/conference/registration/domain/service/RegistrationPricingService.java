package au.edu.uow.csci318.conference.registration.domain.service;

import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Totals what a registration costs, from the prices replicated out of the catalogue.
 *
 * <p>A <strong>domain service</strong> for the same reason as {@link SeatAllocationService}: the
 * sum spans several {@link SessionView} instances and belongs to none of them. Pure, with no
 * dependencies, so it is unit-testable without Spring.
 *
 * <p><strong>Price is captured at request time.</strong> The total this produces is stored on the
 * registration and carried on {@code registration-requested}, so a later catalogue edit can never
 * change what an existing registration owes. Billing never recomputes it.
 *
 * <p>Currency is guarded by {@link Money#plus}, so mixing currencies fails loudly rather than
 * silently adding unlike amounts. Only AUD is in scope, but the guard is what makes that a checked
 * assumption rather than an unwritten one.
 */
public final class RegistrationPricingService {

    /**
     * @param requested session id to seat count
     * @param views     the local replica rows for those sessions
     * @throws SessionNotFoundException if a requested session is unknown or not yet described
     * @throws IllegalArgumentException if the sessions do not share one currency
     */
    public Money total(Map<String, Integer> requested, Map<String, SessionView> views) {
        Money total = null;
        for (Map.Entry<String, Integer> entry : requested.entrySet()) {
            SessionView view = views.get(entry.getKey());
            if (view == null || !view.isComplete()) {
                throw new SessionNotFoundException(entry.getKey());
            }
            Money price = view.getPrice();
            Money line = new Money(
                    price.amount().multiply(BigDecimal.valueOf(entry.getValue())), price.currency());
            total = total == null ? line : total.plus(line);
        }
        if (total == null) {
            throw new IllegalArgumentException("a registration must request at least one session");
        }
        return total;
    }
}
