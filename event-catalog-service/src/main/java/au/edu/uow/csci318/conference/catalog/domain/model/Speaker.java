package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Aggregate root for a person presenting a session.
 *
 * <p>Separate from {@link Event} rather than an entity inside it, because a speaker outlives any
 * single conference and is reused across events. {@code Session} therefore holds a
 * {@code speakerId} and never a {@code Speaker} — referencing another aggregate by identity only
 * is what keeps the two lifecycles independent.
 */
@Entity
@Table(name = "speakers")
public class Speaker {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "bio", length = 2000)
    private String bio;

    @Column(name = "organisation")
    private String organisation;

    protected Speaker() {
        // required by JPA
    }

    private Speaker(String id, String name, String bio, String organisation) {
        this.id = id;
        this.name = name;
        this.bio = bio;
        this.organisation = organisation;
    }

    public static Speaker create(String id, String name, String bio, String organisation) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("speaker id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("speaker name must not be blank");
        }
        return new Speaker(id, name, bio, organisation);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBio() {
        return bio;
    }

    public String getOrganisation() {
        return organisation;
    }
}
