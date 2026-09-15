package au.edu.uow.csci318.conference.payment.domain.model;

/** What the gateway said about one charge attempt. */
public enum AttemptOutcome {
    SUCCEEDED,
    DECLINED,
    TIMED_OUT
}
