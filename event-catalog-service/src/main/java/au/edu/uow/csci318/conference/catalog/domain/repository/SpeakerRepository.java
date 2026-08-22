package au.edu.uow.csci318.conference.catalog.domain.repository;

import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;

import java.util.List;
import java.util.Optional;

/** Persistence port for the {@link Speaker} aggregate. */
public interface SpeakerRepository {

    Speaker save(Speaker speaker);

    Optional<Speaker> findById(String id);

    List<Speaker> findAll();
}
