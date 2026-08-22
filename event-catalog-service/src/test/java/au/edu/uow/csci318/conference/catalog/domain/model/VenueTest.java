package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VenueTest {

    @Test
    void acceptsACompleteVenue() {
        Venue venue = new Venue("Innovation Campus", "Squires Way", "North Wollongong");

        assertThat(venue.city()).isEqualTo("North Wollongong");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new Venue("  ", "Squires Way", "North Wollongong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue name");
    }

    @Test
    void rejectsABlankAddressLine() {
        assertThatThrownBy(() -> new Venue("Innovation Campus", "", "North Wollongong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue address line");
    }

    @Test
    void rejectsABlankCity() {
        assertThatThrownBy(() -> new Venue("Innovation Campus", "Squires Way", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue city");
    }

    @Test
    void isComparedByValueNotIdentity() {
        Venue a = new Venue("Innovation Campus", "Squires Way", "North Wollongong");
        Venue b = new Venue("Innovation Campus", "Squires Way", "North Wollongong");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
