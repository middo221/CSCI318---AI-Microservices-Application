package au.edu.uow.csci318.conference.concierge.domain.model;

/**
 * One ticket, as much of it as a support conversation needs.
 *
 * <p>No holder email and no check-in history. The agent is answering "do I have a ticket, and can
 * I use it", and every field handed to the model is a field handed to a third party.
 */
public record TicketSummary(String ticketCode, String sessionId, String status, String holderName) {
}
