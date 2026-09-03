package au.edu.uow.csci318.conference.registration.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttendeeRefTest {

    @Test
    void carriesTheAttendeeIdentityARegistrationIsMadeFor() {
        AttendeeRef attendee = new AttendeeRef("att-1042", "Dana Okafor", "dana@example.com");

        assertThat(attendee.attendeeId()).isEqualTo("att-1042");
        assertThat(attendee.name()).isEqualTo("Dana Okafor");
        assertThat(attendee.email()).isEqualTo("dana@example.com");
    }

    @Test
    void isEqualByValue() {
        assertThat(new AttendeeRef("att-1", "A", "a@example.com"))
                .isEqualTo(new AttendeeRef("att-1", "A", "a@example.com"));
    }

    @Test
    void trimsSurroundingWhitespace() {
        AttendeeRef attendee = new AttendeeRef("  att-1  ", "  Dana  ", "  dana@example.com  ");

        assertThat(attendee.attendeeId()).isEqualTo("att-1");
        assertThat(attendee.name()).isEqualTo("Dana");
        assertThat(attendee.email()).isEqualTo("dana@example.com");
    }

    @Test
    void requiresAnAttendeeId() {
        assertThatThrownBy(() -> new AttendeeRef("   ", "Dana", "dana@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attendee id");
    }

    @Test
    void requiresAName() {
        assertThatThrownBy(() -> new AttendeeRef("att-1", "  ", "dana@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void requiresSomethingEmailShaped() {
        assertThatThrownBy(() -> new AttendeeRef("att-1", "Dana", "not-an-email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }
}
