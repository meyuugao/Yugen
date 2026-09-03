package me.yuugao.yugen.retry;

import java.util.function.Supplier;

import me.yuugao.yugen.exception.NetworkException;
import me.yuugao.yugen.exception.ProviderException;
import me.yuugao.yugen.exception.RateLimitException;
import me.yuugao.yugen.exception.YugenException;

/**
 * Explicit, testable retry policy with exponential backoff.
 *
 * <p>Retries are classified by exception type: network failures, HTTP 429
 * and 5xx provider errors are transient; 4xx errors are bugs in the request
 * and surface immediately. Backoff delays are pure functions of the failed
 * attempt number, which keeps the policy trivially unit-testable.
 */
public final class RetryPolicy {
    /** Single attempt, no waiting: the null-object policy. */
    public static final RetryPolicy NONE = new RetryPolicy(1, 0, 1.0, 0);

    private final int maxAttempts;
    private final long initialDelayMs;
    private final double multiplier;
    private final long maxDelayMs;

    public RetryPolicy(int maxAttempts, long initialDelayMs, double multiplier, long maxDelayMs) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialDelayMs < 0) {
            throw new IllegalArgumentException("initialDelayMs must be >= 0");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        if (maxDelayMs < 0) {
            throw new IllegalArgumentException("maxDelayMs must be >= 0");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.multiplier = multiplier;
        this.maxDelayMs = maxDelayMs;
    }

    public static RetryPolicy none() {
        return NONE;
    }

    /** 3 attempts, 500 ms start, doubling, 30 s cap. */
    public static RetryPolicy exponential(int maxAttempts, long initialDelayMs) {
        return new RetryPolicy(maxAttempts, initialDelayMs, 2.0, 30_000);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Runs the action, retrying transient failures with exponential backoff.
     *
     * @throws RuntimeException the last failure when attempts are exhausted,
     *         or any non-retryable failure as soon as it occurs
     */
    public <T> T execute(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts || !isRetryable(e)) {
                    throw e;
                }
                sleep(backoffDelayMs(attempt));
            }
        }
    }

    public boolean isRetryable(RuntimeException e) {
        if (e instanceof NetworkException || e instanceof RateLimitException) {
            return true;
        }
        if (e instanceof ProviderException pe) {
            return pe.isRetryable();
        }
        return false;
    }

    /** Delay before retrying the next attempt, given the attempt that just failed. */
    public long backoffDelayMs(int failedAttempt) {
        double delay = initialDelayMs * Math.pow(multiplier, failedAttempt - 1);
        long capped = maxDelayMs > 0 ? Math.min((long) delay, maxDelayMs) : (long) delay;
        return Math.max(capped, 0);
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YugenException("Retry wait interrupted", e);
        }
    }
}