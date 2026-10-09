package au.edu.uow.csci318.conference.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the production {@code application.yml} itself. The test overlay under {@code config/} turns
 * the streams off so the suite needs no broker; if that switch ever leaked into the main file the
 * whole suite would stay green while the service put no message on the wire.
 */
class ProductionConfigGuardTest {

    private static final String AUTO_STARTUP = "spring.kafka.streams.auto-startup";
    private static final String AUTO_CREATE_TOPICS = "spring.cloud.stream.kafka.streams.binder.auto-create-topics";
    private static final String BINDER = "spring.cloud.stream.kafka.streams.binder.";

    private static PropertySource<?> production() throws IOException {
        return new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application.yml")).get(0);
    }

    @Test
    void streamsStartWithTheServiceAndTopicsAreProvisioned() throws IOException {
        PropertySource<?> yml = production();

        assertThat(yml.getProperty(AUTO_STARTUP)).as(AUTO_STARTUP).isIn(null, true, "true");
        assertThat(yml.getProperty(AUTO_CREATE_TOPICS)).as(AUTO_CREATE_TOPICS).isIn(null, true, "true");
    }

    @Test
    void aBadRecordIsLoggedAndSkippedWhetherItFailsToDeserialiseOrToProcess() throws IOException {
        PropertySource<?> yml = production();

        assertThat(yml.getProperty(BINDER + "deserialization-exception-handler")).isEqualTo("logAndContinue");
        assertThat(yml.getProperty(BINDER + "configuration.processing.exception.handler"))
                .isEqualTo("org.apache.kafka.streams.errors.LogAndContinueProcessingExceptionHandler");
    }
}
