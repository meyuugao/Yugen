package me.yuugao.yugen.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {
    @Test
    void rolesMatchWireFormat() {
        assertThat(Message.system("s").role()).isEqualTo("system");
        assertThat(Message.user("u").role()).isEqualTo("user");
        assertThat(Message.assistant("a").role()).isEqualTo("assistant");
    }

    @Test
    void factoriesAndRecordsAreEquivalent() {
        assertThat(Message.user("hi")).isEqualTo(new Message.UserMessage("hi"));
        assertThat(Message.user("hi")).hasSameHashCodeAs(new Message.UserMessage("hi"));
        assertThat(Message.system("s")).isEqualTo(new Message.SystemMessage("s"));
        assertThat(Message.assistant("a")).isEqualTo(new Message.AssistantMessage("a"));
    }

    @Test
    void sealedHierarchySupportsExhaustivePatternMatching() {
        String label = switch (Message.user("hi")) {
            case Message.SystemMessage m -> "sys:" + m.content();
            case Message.UserMessage m -> "usr:" + m.content();
            case Message.AssistantMessage m -> "ast:" + m.content();
        };
        assertThat(label).isEqualTo("usr:hi");
    }

    @Test
    void finishReasonMapsWireValues() {
        assertThat(FinishReason.fromWire("stop")).isEqualTo(FinishReason.STOP);
        assertThat(FinishReason.fromWire("length")).isEqualTo(FinishReason.LENGTH);
        assertThat(FinishReason.fromWire("content_filter")).isEqualTo(FinishReason.CONTENT_FILTER);
        assertThat(FinishReason.fromWire("tool_calls")).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(FinishReason.fromWire("weird-proprietary")).isEqualTo(FinishReason.OTHER);
        assertThat(FinishReason.fromWire(null)).isEqualTo(FinishReason.OTHER);
    }

    @Test
    void usageSumsTokens() {
        assertThat(new Usage(3, 4).totalTokens()).isEqualTo(7);
        assertThat(new Usage(0, 0).totalTokens()).isZero();
    }
}