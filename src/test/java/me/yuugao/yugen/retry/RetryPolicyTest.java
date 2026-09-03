package me.yuugao.yugen.retry;

import org.junit.jupiter.api.Test;

import me.yuugao.yugen.exception.NetworkException;
import me.yuugao.yugen.exception.ProviderException;
import me.yuugao.yugen.exception.RateLimitException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {
    private static final NetworkException NETWORK = new NetworkException("boom", null);

    @Test
    void executesWithoutRetryOnSuccess() {
        int[] calls = {0};
        String result = RetryPolicy.exponential(3, 0).execute(() -> {
            calls[0]++;
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void retriesUntilSuccess() {
        int[] calls = {0};
        String result = RetryPolicy.exponential(3, 0).execute(() -> {
            calls[0]++;
            if (calls[0] < 3) {
                throw NETWORK;
            }
            return "recovered";
        });
        assertThat(result).isEqualTo("recovered");
        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    void throwsLastFailureWhenAttemptsExhausted() {
        int[] calls = {0};
        assertThatThrownBy(() -> RetryPolicy.exponential(3, 0).execute(() -> {
            calls[0]++;
            throw NETWORK;
        })).isSameAs(NETWORK);
        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    void doesNotRetryNonRetryableFailures() {
        int[] calls = {0};
        IllegalStateException bug = new IllegalStateException("caller bug");
        assertThatThrownBy(() -> RetryPolicy.exponential(3, 0).execute(() -> {
            calls[0]++;
            throw bug;
        })).isSameAs(bug);
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void singleAttemptPolicyNeverRetries() {
        int[] calls = {0};
        assertThatThrownBy(() -> RetryPolicy.none().execute(() -> {
            calls[0]++;
            throw NETWORK;
        })).isSameAs(NETWORK);
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void classifiesRetryableExceptions() {
        RetryPolicy policy = RetryPolicy.none();

        assertThat(policy.isRetryable(NETWORK)).isTrue();
        assertThat(policy.isRetryable(new RateLimitException(null, "limited"))).isTrue();
        assertThat(policy.isRetryable(new ProviderException(503, "down"))).isTrue();
        assertThat(policy.isRetryable(new ProviderException(500, "crash"))).isTrue();
        assertThat(policy.isRetryable(new ProviderException(400, "bad request"))).isFalse();
        assertThat(policy.isRetryable(new ProviderException(401, "unauthorized"))).isFalse();
        assertThat(policy.isRetryable(new IllegalStateException())).isFalse();
    }

    @Test
    void backoffGrowsExponentiallyAndCaps() {
        RetryPolicy policy = new RetryPolicy(5, 100, 2.0, 350);

        assertThat(policy.backoffDelayMs(1)).isEqualTo(100);
        assertThat(policy.backoffDelayMs(2)).isEqualTo(200);
        assertThat(policy.backoffDelayMs(3)).isEqualTo(350);
        assertThat(policy.backoffDelayMs(4)).isEqualTo(350);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RetryPolicy(0, 0, 1.0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, -1, 1.0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, 0, 0.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, 0, 1.0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}