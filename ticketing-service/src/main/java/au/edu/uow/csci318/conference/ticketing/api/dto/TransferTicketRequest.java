package au.edu.uow.csci318.conference.ticketing.api.dto;

import au.edu.uow.csci318.conference.ticketing.application.command.TransferTicketCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/tickets/{ticketCode}/transfer}.
 *
 * <p>Names only the new holder. The previous one is whoever holds the ticket now, which the
 * aggregate already knows — asking the caller for it would let them assert a hand-over that never
 * happened and write a false entry into an append-only history.
 *
 * <p>A transport type, deliberately separate from the command it becomes. Bean Validation catches
 * the shape of the request so the domain never sees malformed input; {@code Holder} still
 * validates again, because it cannot assume this layer ran.
 */
public record TransferTicketRequest(
        @NotBlank(message = "must be supplied") String toHolderName,
        @NotBlank(message = "must be supplied") @Email(message = "must be an email address") String toHolderEmail) {

    public TransferTicketCommand toCommand(String ticketCode) {
        return new TransferTicketCommand(ticketCode, toHolderName, toHolderEmail);
    }
}
