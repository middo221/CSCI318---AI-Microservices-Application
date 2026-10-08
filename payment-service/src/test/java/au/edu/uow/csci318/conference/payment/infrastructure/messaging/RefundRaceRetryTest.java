package au.edu.uow.csci318.conference.payment.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RefundRequested;
import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.application.RefundApplicationService;
import au.edu.uow.csci318.conference.payment.application.command.DecideRefundCommand;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two refund decisions for one order can both pass {@code requireRefundable()}; the loser's lock
 * failure surfaces at commit, outside {@code decide}'s body. The consumer decides once more, in a
 * fresh transaction, where the order is already refunded and registration is told so.
 */
class RefundRaceRetryTest {

    @Test
    void decidesOnceMoreWhenTheCommitLosesARace() {
        ProcessedEventStore processed = mock(ProcessedEventStore.class);
        when(processed.markProcessed(any(), any())).thenReturn(true);
        RefundApplicationService refunds = mock(RefundApplicationService.class);
        when(refunds.decide(any(DecideRefundCommand.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException("PaymentOrder", "order-1"))
                .thenReturn(Optional.empty());
        var consumer = new RegistrationEventConsumers(processed, mock(PaymentApplicationService.class), refunds)
                .refundRequested();

        RefundRequested event = new RefundRequested(UUID.randomUUID().toString(), Instant.now(),
                "reg-1", "att-1", "cannot attend",
                RefundRequested.REQUESTED_BY_ATTENDEE, RefundRequested.KIND_ATTENDEE_REQUEST);

        assertThatCode(() -> consumer.accept(MessageBuilder.withPayload(event).build()))
                .as("a lost race must not be rethrown, or the partition is redelivered forever")
                .doesNotThrowAnyException();
        verify(refunds, times(2)).decide(any(DecideRefundCommand.class));
    }
}
