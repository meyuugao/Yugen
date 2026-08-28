package me.yuugao.yugen.exception;

/**
 * The provider answered with an HTTP error. Carries the status code so
 * callers (and {@link me.yuugao.yugen.retry.RetryPolicy}) can classify
 * failures without parsing message strings.
 */
public class ProviderException extends YugenException {

    private final int statusCode;

    public ProviderException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Server-side failures are usually transient; client errors are not. */
    public boolean isRetryable() {
        return statusCode >= 500;
    }
}
