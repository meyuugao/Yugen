package me.yuugao.yugen.provider;

import me.yuugao.yugen.chat.ChatRequest;
import me.yuugao.yugen.chat.ChatResponse;

/**
 * Provider SPI. Implementations translate a {@link ChatRequest} into their
 * wire format and a wire response back into {@link ChatResponse}.
 *
 * <p>Streaming will be added as a default-throwing method in the SSE phase
 * so existing implementations keep compiling.
 */
public interface LlmProvider extends AutoCloseable {

    /** Stable provider identifier, e.g. {@code "openai"}. */
    String name();

    ChatResponse chat(ChatRequest request);

    @Override
    default void close() {
    }
}
