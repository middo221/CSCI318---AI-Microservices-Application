package au.edu.uow.csci318.conference.payment.domain.model;

/** Who made a refund decision (specification §4.3). The name travels as {@code decidedBy}. */
public enum DecisionMaker {

    /** The published policy, applied automatically to an attendee's request or a late-payment reversal. */
    POLICY,

    /** The support agent (FR-18) asked; the policy still set the percentage, within the agent's threshold. */
    AGENT,

    /** An organiser decided by hand — how a human resolves an escalation from the agent. */
    ORGANISER
}
