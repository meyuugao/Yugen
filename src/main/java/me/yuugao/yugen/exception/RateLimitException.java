package me.yuugao.yugen.exception;

/** HTTP 429 from the provider. Exposes {@code Retry-After} when present. */
public class RateLimitException extends ProviderException {

    private final Long retryAfterSeconds;

    public RateLimitException(Long retryAfterSeconds, String message) {
        super(429, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Nullable: seconds requested by the server, absent when no header was sent. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
