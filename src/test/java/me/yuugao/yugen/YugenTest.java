package me.yuugao.yugen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import me.yuugao.yugen.chat.ChatRequest;
import me.yuugao.yugen.chat.ChatResponse;
import me.yuugao.yugen.chat.ChatStream;
import me.yuugao.yugen.chat.FinishReason;
import me.yuugao.yugen.chat.Message;
import me.yuugao.yugen.chat.Usage;
import me.yuugao.yugen.provider.LlmProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YugenTest {
    private static ChatResponse ok() {
        return new ChatResponse("done", "test-model", FinishReason.STOP, new Usage(1, 2));
    }

    @Test
    void chatStringSendsPromptWithDefaultModel() {
        List<ChatRequest> seen = new CopyOnWriteArrayList<>();
        LlmProvider stub = new LlmProvider() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                seen.add(request);
                return ok();
            }
        };

        Yugen withStub = stubFacade(stub, "stub-model");
        ChatResponse response = withStub.chat("hello");

        assertThat(response.content()).isEqualTo("done");
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().model()).isEqualTo("stub-model");
        assertThat(seen.getFirst().messages()).containsExactly(
                Message.user("hello"));
    }

    @Test
    void chatStreamDelegatesToProvider() {
        List<ChatRequest> seen = new CopyOnWriteArrayList<>();
        LlmProvider stub = new LlmProvider() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                return ok();
            }

            @Override
            public ChatStream chatStream(ChatRequest request) {
                seen.add(request);
                return new ChatStream(
                        Arrays.asList(
                                new ChatStream.Event("a", "m", null, null),
                                new ChatStream.Event("b", null, FinishReason.STOP, new Usage(2, 3))).iterator(),
                        () -> {});
            }
        };

        Yugen yugen = stubFacade(stub, "stub-model");
        try (ChatStream stream = yugen.chatStream("hi")) {
            List<String> tokens = new ArrayList<>();
            stream.forEach(tokens::add);
            assertThat(tokens).containsExactly("a", "b");
            assertThat(stream.await().usage().totalTokens()).isEqualTo(5);
        }
        assertThat(seen.getFirst().messages()).containsExactly(
                Message.user("hi"));
    }

    @Test
    void providersWithoutStreamingThrowUnsupportedByDefault() {
        LlmProvider syncOnly = new LlmProvider() {
            @Override
            public String name() {
                return "sync-only";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                return ok();
            }
        };

        Yugen yugen = stubFacade(syncOnly, "m");
        assertThatThrownBy(() -> yugen.chatStream("hi"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("sync-only");
    }

    @Test
    void closeClosesTheProvider() {
        boolean[] closed = {false};
        LlmProvider stub = new LlmProvider() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                return ok();
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };

        try (Yugen yugen = stubFacade(stub, "m")) {
            yugen.chat("hi");
        }
        assertThat(closed[0]).isTrue();
    }

    private static Yugen stubFacade(LlmProvider provider, String model) {
        return Yugen.builder().provider(provider).model(model).build();
    }
}