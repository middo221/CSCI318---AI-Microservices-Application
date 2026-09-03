package au.edu.uow.csci318.conference.registration.domain.service;

import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationPricingServiceTest {

    private static final RegistrationPricingService PRICING = new RegistrationPricingService();
    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    private static SessionView priced(String id, Money price) {
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, price);
        view.setCapacity(120);
        return view;
    }

    @Test
    void multipliesEachSessionPriceByTheSeatsTaken() {
        Map<String, SessionView> views = Map.of("ses-1", priced("ses-1", Money.aud("49.50")));

        assertThat(PRICING.total(Map.of("ses-1", 3), views)).isEqualTo(Money.aud("148.50"));
    }

    @Test
    void sumsAcrossEverySessionInTheRegistration() {
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put("ses-1", 2);
        requested.put("ses-2", 1);
        Map<String, SessionView> views = Map.of(
                "ses-1", priced("ses-1", Money.aud("49.50")),
                "ses-2", priced("ses-2", Money.aud("25.00")));

        assertThat(PRICING.total(requested, views)).isEqualTo(Money.aud("124.00"));
    }

    @Test
    void totalsAFreeProgrammeAtZero() {
        Map<String, SessionView> views = Map.of("ses-1", priced("ses-1", Money.aud("0.00")));

        assertThat(PRICING.total(Map.of("ses-1", 2), views)).isEqualTo(Money.aud("0.00"));
    }

    /**
     * Price is captured at request time and carried on {@code registration-requested}, so a later
     * catalogue edit can never change what an existing registration owes.
     */
    @Test
    void readsThePriceReplicatedFromTheCatalogue() {
        SessionView view = priced("ses-1", Money.aud("49.50"));
        Map<String, SessionView> views = Map.of("ses-1", view);

        Money before = PRICING.total(Map.of("ses-1", 1), views);
        view.describe("conf-1", "Session ses-1", MORNING, Money.aud("99.00"));

        assertThat(before).isEqualTo(Money.aud("49.50"));
        assertThat(PRICING.total(Map.of("ses-1", 1), views)).isEqualTo(Money.aud("99.00"));
    }

    @Test
    void refusesToTotalUnlikeCurrencies() {
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put("ses-1", 1);
        requested.put("ses-2", 1);
        Map<String, SessionView> views = Map.of(
                "ses-1", priced("ses-1", Money.aud("49.50")),
                "ses-2", priced("ses-2", new Money(new BigDecimal("25.00"), "USD")));

        assertThatThrownBy(() -> PRICING.total(requested, views))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void refusesASessionThisServiceHasNeverHeardOf() {
        assertThatThrownBy(() -> PRICING.total(Map.of("ses-unknown", 1), Map.of()))
                .isInstanceOf(SessionNotFoundException.class);
    }
}
