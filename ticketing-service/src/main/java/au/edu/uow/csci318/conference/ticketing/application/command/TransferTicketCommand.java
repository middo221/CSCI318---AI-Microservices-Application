package au.edu.uow.csci318.conference.ticketing.application.command;

/** Hands a ticket to somebody else (FR-12). */
public record TransferTicketCommand(String ticketCode, String toHolderName, String toHolderEmail) {
}
