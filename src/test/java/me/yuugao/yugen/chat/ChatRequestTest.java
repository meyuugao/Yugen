package me.yuugao.yugen.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRequestTest {

    @Test
    void buildsRequestWithConvenienceHelpers() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o-mini")
                .system("be terse")
                .user("hi")
                .assistant("hello")
                .user("again")
                .temperature(0.5)
                .maxTokens(128)
                .build();

        assertThat(request.model()).isEqualTo("gpt-4o-mini");
        assertThat(request.messages()).containsExactly(
                Message.system("be terse"),
                Message.user("hi"),
                Message.assistant("hello"),
                Message.user("again"));
        assertThat(request.temperature()).isEqualTo(0.5);
        assertThat(request.maxTokens()).isEqualTo(128);
    }

    @Test
    void defaultsToNullForOptionalParameters() {
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .user("hi")
                .build();

        assertThat(request.temperature()).isNull();
        assertThat(request.maxTokens()).isNull();
    }

    @Test
    void messagesAreDefensivelyCopied() {
        List<Message> source = new ArrayList<>();
        source.add(Message.user("one"));

        ChatRequest request = ChatRequest.builder()
                .model("m")
                .messages(source)
                .build();

        source.add(Message.user("two"));

        assertThat(request.messages()).hasSize(1);
        assertThatThrownBy(() -> request.messages().add(Message.user("three")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresModelAndMessages() {
        assertThatThrownBy(() -> ChatRequest.builder().user("hi").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(() -> ChatRequest.builder().model("m").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message");
    }
}
