package me.yuugao.yugen.chat;

/**
 * A chat message in a conversation.
 *
 * <p>Sealed hierarchy: the set of roles is closed by design, so consumers can
 * switch over messages with compiler-verified exhaustiveness instead of
 * string-matching on {@code role} fields. Tool messages will join this
 * hierarchy together with the tool-calling phase.
 */
public sealed interface Message {

    /** Wire role name, e.g. {@code "user"}. */
    String role();

    /** Message text content. */
    String content();

    record SystemMessage(String content) implements Message {
        @Override
        public String role() {
            return "system";
        }
    }

    record UserMessage(String content) implements Message {
        @Override
        public String role() {
            return "user";
        }
    }

    record AssistantMessage(String content) implements Message {
        @Override
        public String role() {
            return "assistant";
        }
    }

    static Message system(String content) {
        return new SystemMessage(content);
    }

    static Message user(String content) {
        return new UserMessage(content);
    }

    static Message assistant(String content) {
        return new AssistantMessage(content);
    }
}
