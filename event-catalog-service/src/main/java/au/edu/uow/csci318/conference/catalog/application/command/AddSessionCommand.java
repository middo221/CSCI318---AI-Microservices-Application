package au.edu.uow.csci318.conference.catalog.application.command;

import au.edu.uow.csci318.conference.catalog.domain.model.Price;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.catalog.domain.model.Track;

/** Presentation-independent input for adding a session to an event. */
public record AddSessionCommand(String title,
                                Track track,
                                TimeSlot timeSlot,
                                String speakerId,
                                SeatCapacity seatCapacity,
                                Price price) {
}
