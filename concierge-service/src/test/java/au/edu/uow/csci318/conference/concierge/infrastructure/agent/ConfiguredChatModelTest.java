package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import au.edu.uow.csci318.conference.concierge.application.LlmUnavailableException;
import au.edu.uow.csci318.conference.concierge.support.ScriptedChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one decision both agents share: is there a model to talk to? */
class ConfiguredChatModelTest {

    private static ObjectProvider<ChatModel> providing(ChatModel model) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (model != null) {
            beans.registerSingleton("chatModel", model);
        }
        return beans.getBeanProvider(ChatModel.class);
    }

    /** An empty key is a present property to @ConditionalOnProperty; the starter would build a model that cannot log in. */
    @Test
    void treatsABlankKeyAsNoModelEvenWhenTheStarterBuiltOne() {
        ConfiguredChatModel configured = new ConfiguredChatModel(providing(new ScriptedChatModel()), "  ");

        assertThatThrownBy(configured::require)
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void reportsNoModelWhenNoneIsRegistered() {
        assertThatThrownBy(new ConfiguredChatModel(providing(null), "a-key")::require)
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void returnsTheConfiguredModel() {
        ScriptedChatModel model = new ScriptedChatModel();

        assertThat(new ConfiguredChatModel(providing(model), "a-key").require()).isSameAs(model);
    }
}
