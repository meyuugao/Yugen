package me.yuugao.yugen.chat;

/** Why the model stopped generating, normalized across providers. */
public enum FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL_CALLS,
    OTHER;

    /** Maps a provider wire value; unknown or missing values become {@link #OTHER}. */
    public static FinishReason fromWire(String wire) {
        if (wire == null) {
            return OTHER;
        }
        return switch (wire) {
            case "stop" -> STOP;
            case "length" -> LENGTH;
            case "content_filter" -> CONTENT_FILTER;
            case "tool_calls", "function_call" -> TOOL_CALLS;
            default -> OTHER;
        };
    }
}