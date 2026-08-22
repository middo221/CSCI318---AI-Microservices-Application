package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;
import au.edu.uow.csci318.conference.catalog.domain.repository.SpeakerRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Adapts Spring Data JPA to the domain's {@link SpeakerRepository} port. */
@Repository
public class JpaSpeakerRepository implements SpeakerRepository {

    private final SpeakerJpaRepository delegate;

    public JpaSpeakerRepository(SpeakerJpaRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Speaker save(Speaker speaker) {
        return delegate.save(speaker);
    }

    @Override
    public Optional<Speaker> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public List<Speaker> findAll() {
        return delegate.findAll();
    }
}
