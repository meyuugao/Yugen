package me.yuugao.yugen.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable chat request. Optional parameters stay {@code null} so the
 * provider applies its own defaults instead of the library guessing them.
 */
public final class ChatRequest {
    private final String model;
    private final List<Message> messages;
    private final Double temperature;
    private final Integer maxTokens;

    private ChatRequest(Builder builder) {
        this.model = builder.model;
        this.messages = List.copyOf(builder.messages);
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String model() {
        return model;
    }

    /** Unmodifiable snapshot of the conversation. */
    public List<Message> messages() {
        return messages;
    }

    /** Nullable: provider default when absent. */
    public Double temperature() {
        return temperature;
    }

    /** Nullable: provider default when absent. */
    public Integer maxTokens() {
        return maxTokens;
    }

    public static final class Builder {
        private String model;
        private final List<Message> messages = new ArrayList<>();
        private Double temperature;
        private Integer maxTokens;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<Message> messages) {
            Objects.requireNonNull(messages, "messages");
            this.messages.clear();
            this.messages.addAll(messages);
            return this;
        }

        public Builder addMessage(Message message) {
            this.messages.add(Objects.requireNonNull(message, "message"));
            return this;
        }

        public Builder system(String content) {
            return addMessage(Message.system(content));
        }

        public Builder user(String content) {
            return addMessage(Message.user(content));
        }

        public Builder assistant(String content) {
            return addMessage(Message.assistant(content));
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public ChatRequest build() {
            if (model == null || model.isBlank()) {
                throw new IllegalStateException("model is required");
            }
            if (messages.isEmpty()) {
                throw new IllegalStateException("at least one message is required");
            }
            return new ChatRequest(this);
        }
    }
}