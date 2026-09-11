package au.edu.uow.csci318.conference.ticketing.infrastructure.persistence;

import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import au.edu.uow.csci318.conference.ticketing.domain.repository.TicketRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Adapts Spring Data to the domain's {@link TicketRepository} port. */
@Repository
public class JpaTicketRepository implements TicketRepository {

    private final TicketJpaRepository rows;

    public JpaTicketRepository(TicketJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public Ticket save(Ticket ticket) {
        return rows.save(ticket);
    }

    @Override
    public Optional<Ticket> findByTicketCode(String ticketCode) {
        return rows.findByTicketCode_Value(ticketCode);
    }

    @Override
    public List<Ticket> findByRegistrationId(String registrationId) {
        return rows.findByRegistrationId(registrationId);
    }

    @Override
    public boolean existsByRegistrationIdAndSessionId(String registrationId, String sessionId) {
        return rows.existsByRegistrationIdAndSessionId(registrationId, sessionId);
    }
}
