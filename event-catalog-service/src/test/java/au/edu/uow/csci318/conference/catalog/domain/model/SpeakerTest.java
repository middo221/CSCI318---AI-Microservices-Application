package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeakerTest {

    @Test
    void createsASpeakerWithEveryDetail() {
        Speaker speaker = Speaker.create("spk-1", "Dr Chen", "Works on observability", "UOW");

        assertThat(speaker.getId()).isEqualTo("spk-1");
        assertThat(speaker.getName()).isEqualTo("Dr Chen");
        assertThat(speaker.getBio()).isEqualTo("Works on observability");
        assertThat(speaker.getOrganisation()).isEqualTo("UOW");
    }

    @Test
    void allowsAnAbsentBioAndOrganisation() {
        Speaker speaker = Speaker.create("spk-2", "Dr Chen", null, null);

        assertThat(speaker.getBio()).isNull();
        assertThat(speaker.getOrganisation()).isNull();
    }

    @Test
    void rejectsABlankId() {
        assertThatThrownBy(() -> Speaker.create("  ", "Dr Chen", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("speaker id");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> Speaker.create("spk-3", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("speaker name");
    }
}
