package au.edu.uow.csci318.conference.catalog.application;

import au.edu.uow.csci318.conference.catalog.application.command.CreateSpeakerCommand;
import au.edu.uow.csci318.conference.catalog.domain.SpeakerNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;
import au.edu.uow.csci318.conference.catalog.domain.repository.SpeakerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Orchestrates speaker use cases and owns the transaction boundary. */
@Service
public class SpeakerApplicationService {

    private final SpeakerRepository speakers;

    public SpeakerApplicationService(SpeakerRepository speakers) {
        this.speakers = speakers;
    }

    @Transactional
    public Speaker create(CreateSpeakerCommand command) {
        return speakers.save(Speaker.create(
                UUID.randomUUID().toString(),
                command.name(),
                command.bio(),
                command.organisation()));
    }

    @Transactional(readOnly = true)
    public Speaker getById(String id) {
        return speakers.findById(id).orElseThrow(() -> new SpeakerNotFoundException(id));
    }
}
