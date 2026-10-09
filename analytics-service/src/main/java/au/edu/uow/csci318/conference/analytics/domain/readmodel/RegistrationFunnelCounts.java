package au.edu.uow.csci318.conference.analytics.domain.readmodel;

import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;

/** Reads the registration funnel. Throws {@link ReadModelUnavailableException} while the store cannot be queried. */
public interface RegistrationFunnelCounts {

    /** Zeros, never empty, before any registration has been seen. */
    RegistrationFunnel current();
}
