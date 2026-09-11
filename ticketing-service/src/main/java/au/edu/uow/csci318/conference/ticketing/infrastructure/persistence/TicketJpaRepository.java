package au.edu.uow.csci318.conference.ticketing.infrastructure.persistence;

import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data plumbing. Package-private so the port is the only way in from outside. */
interface TicketJpaRepository extends JpaRepository<Ticket, String> {

    /** Navigates into the embedded {@code TicketCode} to match on its single column. */
    Optional<Ticket> findByTicketCode_Value(String ticketCode);

    List<Ticket> findByRegistrationId(String registrationId);

    boolean existsByRegistrationIdAndSessionId(String registrationId, String sessionId);
}
