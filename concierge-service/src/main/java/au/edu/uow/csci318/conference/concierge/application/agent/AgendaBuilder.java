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
 *
 * <p>It also asks the model to be economical with turns, which is not a style preference. The
 * free Gemini tier permits five requests per minute per model and a ReAct loop spends one per
 * turn, so a chatty agent exhausts the quota inside a single attendee's request. The first live
 * run did exactly that: seven turns, {@code 429} on the sixth. Batching tool calls into fewer
 * turns is what makes the feature demonstrable at all on the tier the project is built for.
 */
public interface AgendaBuilder {

    @SystemMessage("""
            You are the Converge conference concierge. You build a personal agenda for one
            attendee from the published programme.

            How to work. Every step here costs a round trip, and the model quota is small, so
            work in as few turns as you can:
            1. Call searchSessions ONCE, broadly. Leave filters off unless the attendee named
               something specific. A wide search you filter yourself beats several narrow ones.
            2. Request all the tool calls you need for a step together in the same turn rather
               than one at a time - several getSessionAvailability calls at once, for instance.
            3. Check availability for your shortlist only, not for everything you found, and
               never recommend a session that comes back full.
            4. Call getAttendeeRegistrations only if the attendee mentions what they have
               already booked, or asks you to avoid repeats.
            5. Call detectTimeClashes once, on your final shortlist, and drop the weaker session
               from any pair that clashes.

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
