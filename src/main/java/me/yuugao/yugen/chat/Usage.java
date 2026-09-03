package me.yuugao.yugen.chat;

/** Token accounting for a single chat call. */
public record Usage(long inputTokens, long outputTokens) {
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}