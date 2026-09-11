package au.edu.uow.csci318.conference.ticketing.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketCodeTest {

    @Test
    void generatesAUrlSafeCodeBehindAReadablePrefix() {
        assertThat(TicketCode.generate().value()).matches("TKT-[A-Za-z0-9_-]+");
    }

    /**
     * Opaque means unguessable. A code is the only credential presented at a gate, so two issued
     * back to back must not collide and must not be derivable from one another.
     */
    @Test
    void generatesADifferentCodeEveryTime() {
        Set<String> codes = Stream.generate(TicketCode::generate)
                .limit(500)
                .map(TicketCode::value)
                .collect(Collectors.toSet());

        assertThat(codes).hasSize(500);
    }

    @Test
    void rejectsABlankCode() {
        assertThatThrownBy(() -> new TicketCode("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticket code");
    }

    @Test
    void rejectsANullCode() {
        assertThatThrownBy(() -> new TicketCode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticket code");
    }

    /**
     * The code travels in a URL path segment — {@code GET /api/v1/tickets/{ticketCode}} — so
     * anything needing percent-encoding is refused at construction rather than mangled in transit.
     */
    @Test
    void rejectsACodeThatIsNotUrlSafe() {
        assertThatThrownBy(() -> new TicketCode("TKT/ABC 123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL-safe");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(new TicketCode("  TKT-ABC123 ")).isEqualTo(new TicketCode("TKT-ABC123"));
    }

    @Test
    void isEqualByValue() {
        assertThat(new TicketCode("TKT-ABC123")).isEqualTo(new TicketCode("TKT-ABC123"));
    }
}
