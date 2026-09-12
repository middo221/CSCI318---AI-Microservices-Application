package au.edu.uow.csci318.conference.payment.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "payment_orders")
public class PaymentOrder {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "registration_id", nullable = false, unique = true, length = 64)
    private String registrationId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(
                    name = "amount",
                    column = @Column(name = "amount", precision = 12, scale = 2, nullable = false)),
            @AttributeOverride(
                    name = "currency",
                    column = @Column(name = "currency", length = 3, nullable = false))
    })
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected PaymentOrder() {
        // required by JPA
    }

    private PaymentOrder(String id, String registrationId, Money amount) {
        this.id = id;
        this.registrationId = registrationId;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    public static PaymentOrder create(String registrationId, Money amount) {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("registration id must be supplied");
        }
        if (amount == null) {
            throw new IllegalArgumentException("payment amount must be supplied");
        }

        return new PaymentOrder(
                UUID.randomUUID().toString(),
                registrationId,
                amount);
    }

    public void markPaid() {
        requirePending();

        status = PaymentStatus.PAID;

        domainEvents.add(new PaymentSucceeded(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                registrationId,
                amount.amount(),
                amount.currency()));
    }

    public void markFailed(String reason) {
        requirePending();

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("failure reason must be supplied");
        }

        status = PaymentStatus.FAILED;

        domainEvents.add(new PaymentFailed(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                registrationId,
                reason));
    }

    private void requirePending() {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "payment order " + id + " is already " + status);
        }
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    public String getId() {
        return id;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public Money getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }
}