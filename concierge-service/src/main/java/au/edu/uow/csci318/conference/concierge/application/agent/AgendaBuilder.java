package au.edu.uow.csci318.conference.concierge.application.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Agent 1, the Agenda Builder (FR-17).
 *
 * <p>A LangChain4j {@code AiServices} interface. There is no implementation in this repository:
 * the proxy is generated at runtime, drives the plan-act-observe loop against the tools it is
 * built with, and parses the model's final message into {@link ProposedAgenda}.
 *
 * <p>The system message below is guidance, not enforcement. Everything in it can be ignored by
 * the model, so nothing the feature actually promises rests on it. Clash-freeness and the ban on
 * invented sessions are both re-checked in deterministic Java after this method returns. The
 * prompt exists to make the model's first attempt a good one, not to make a bad one impossible.
 */
public interface AgendaBuilder {

    @SystemMessage("""
            You are the Converge conference concierge. You build a personal agenda for one
            attendee from the published programme.

            How to work:
            1. Call searchSessions to find candidates. Search more than once if the attendee
               mentions several interests - one search per theme finds more than one broad search.
            2. Call getSessionAvailability for each session you are seriously considering, and
               never recommend one that comes back full.
            3. Call getAttendeeRegistrations so you do not recommend something already booked.
            4. Call detectTimeClashes on your shortlist before you answer, and drop the weaker
               session from any pair that clashes.

            Rules:
            - Only ever return session ids that came back from searchSessions. Never invent one.
            - Respect soft preferences in the attendee's own words, such as keeping an afternoon
              free or following a particular speaker.
            - Order the agenda by how well it matches what the attendee asked for, best first.
            - Give a short, specific reason for each session, in plain British English.
            - If nothing suitable exists, return an empty list and say so in the summary.
            """)
    @UserMessage("""
            Attendee id: {{attendeeId}}

            In their own words, what they are interested in:
            {{interests}}
            """)
    ProposedAgenda buildAgenda(@V("attendeeId") String attendeeId,
                               @V("interests") String interests);
}
