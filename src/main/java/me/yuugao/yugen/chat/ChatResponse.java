package me.yuugao.yugen.chat;

/** Provider-agnostic result of a completed (non-streaming) chat call. */
public record ChatResponse(
        String content,
        String model,
        FinishReason finishReason,
        Usage usage
) {
}