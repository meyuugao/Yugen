package me.yuugao.yugen;

import me.yuugao.yugen.chat.ChatRequest;
import me.yuugao.yugen.chat.ChatResponse;
import me.yuugao.yugen.chat.ChatStream;
import me.yuugao.yugen.provider.LlmProvider;
import me.yuugao.yugen.provider.openai.OpenAiProvider;
import me.yuugao.yugen.retry.RetryPolicy;

/**
 * Entry point facade. Keeps the happy path one-liner-short while delegating all real work to the configured {@link LlmProvider}
 *
 * <pre>
 *     {@code
 * try (Yugen yugen = Yugen.builder()
 *         .openAi(System.getenv("OPENAI_API_KEY"))
 *         .retry(RetryPolicy.exponential(3, 500))
 *         .build()) {
 *     ChatResponse r = yugen.chat("Explain virtual threads briefly.");
 * }
 * }</pre>
 */
public final class Yugen implements AutoCloseable {
    private final LlmProvider provider;
    private final String defaultModel;

    private Yugen(LlmProvider provider, String defaultModel) {
        this.provider = provider;
        this.defaultModel = defaultModel;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Shorthand: single user prompt with the facade's default model.
     */
    public ChatResponse chat(String prompt) {
        return chat(ChatRequest.builder()
                .model(defaultModel)
                .user(prompt)
                .build());
    }

    public ChatResponse chat(ChatRequest chatRequest) {
        return provider.chat(chatRequest);
    }

    /**
     * Streams the answer to a single user prompt, token by token.
     *
     * <p>Convenience overload of {@link #chatStream(ChatRequest)} using the
     * facade's default model.
     *
     * <pre>{@code
     * try (ChatStream stream = yugen.chatStream("Tell me a haiku.")) {
     *     stream.forEach(System.out::print);
     *     ChatResponse full = stream.await();
     * }
     * }</pre>
     *
     * @param prompt user message text
     * @return live stream over the response; close it with try-with-resources
     */
    public ChatStream chatStream(String prompt) {
        return chatStream(ChatRequest.builder()
                .model(defaultModel)
                .user(prompt)
                .build());
    }

    /**
     * Streams the answer to a fully specified request, token by token.
     *
     * @param request conversation and parameters to send
     * @return live stream over the response; close it with try-with-resources
     * @throws UnsupportedOperationException if the underlying provider
     *         cannot stream
     */
    public ChatStream chatStream(ChatRequest request) {
        return provider.chatStream(request);
    }

    public LlmProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        provider.close();
    }

    public static final class Builder {
        private LlmProvider customProvider;
        private String apiKey;
        private String baseUrl = OpenAiProvider.DEFAULT_BASE_URL;
        private String model = "gpt-4o-mini";
        private RetryPolicy retry = RetryPolicy.NONE;

        /**
         * Configures the OpenAI-compatible provider with an API key.
         */
        public Builder openAi(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Uses a custom {@link LlmProvider} implementation instead of the
         * built-in OpenAI-compatible one.
         *
         * <p>The injection point for alternative providers (Anthropic,
         * native Ollama) and for test doubles. Closing the facade closes
         * the injected provider as well.
         *
         * @param customProvider provider to route all calls through
         * @return this builder
         */
        public Builder provider(LlmProvider customProvider) {
            this.customProvider = customProvider;
            return this;
        }

        /**
         * Points the client at any OpenAI-compatible endpoint:
         * Ollama ({@code http://localhost:11434/v1}). OpenRouter, DeepSeek, Groq...
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder retry(RetryPolicy retry) {
            this.retry = retry;
            return this;
        }


        public Yugen build() {
            if (customProvider != null) {
                return new Yugen(customProvider, model);
            }

            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("apiKey is required: call openAi(...)");
            }

            LlmProvider provider = OpenAiProvider.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .retry(retry)
                    .build();
            return new Yugen(provider, model);
        }
    }
}