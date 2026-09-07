package me.yuugao.yugen.provider;

import me.yuugao.yugen.chat.ChatRequest;
import me.yuugao.yugen.chat.ChatResponse;
import me.yuugao.yugen.chat.ChatStream;

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

    /**
     * Streams the answer token by token
     *
     * <p>
     *     The returned {@link ChatStream} is single-use and must be closed;
     *     try-with-sources is the intended shape. Unlike the sync path,
     *     streaming calls are generally not retried by implementations: tokens
     *     may already have been delivered, and replaying them would duplicate visible output.
     * </p>
     *
     * @param request conversation and parameters to send
     * @return live stream of the response
     * @throws UnsupportedOperationException if the implementation cannot stream
     */
    default ChatStream chatStream(ChatRequest request) {
        throw new UnsupportedOperationException("%s does not support streaming".formatted(name()));
    }

    @Override
    default void close() {
    }
}