package au.edu.uow.csci318.conference.ticketing.domain.model;

/**
 * Where a ticket is in its life.
 *
 * <p>{@link #TRANSFERRED} is a state rather than a flag because it is not interchangeable with
 * {@link #ISSUED}: a transferred ticket still admits somebody, but the person it admits is no
 * longer the attendee who paid, and a gate turning away the original registrant needs to be able
 * to say why.
 *
 * <p>{@link #VOID} and {@link #USED} are both terminal, and for different reasons. A void ticket
 * will never admit anybody; a used one already has.
 */
public enum TicketStatus {

    /** Freshly issued against a confirmed session, held by the attendee who registered. */
    ISSUED,

    /** Handed to somebody else. Still admissible — by the new holder. */
    TRANSFERRED,

    /** Withdrawn after a refund or a cancelled event. Admits nobody, and cannot be transferred. */
    VOID,

    /** Already presented at a gate. A ticket may be checked in at most once. */
    USED
}
