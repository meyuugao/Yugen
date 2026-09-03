package me.yuugao.yugen.exception;

/**
 * The provider answered with a success status, but the payload could not be
 * interpreted: unparseable JSON, unexpected structure, or an empty choices list.
 *
 * <p>
 *     Kept separate from {@link ProviderException} because the HTTP layer
 *     succeeded - the failure is in the body, not the status, so there is no
 *     meaningful status code to carry. Not classified as retryable by default:
 *     a provider returning well-formed requests with malformed bodies usually
 *     needs a human to inspect the exchange rather than another attempt.
 * </p>
 */
public class MalformedResponseException extends YugenException {
    /**
     * Creates an exception describing the payload defect.
     *
     * @param message description of what was expected versus received
     */
    public MalformedResponseException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and underlying parse cause.
     *
     * @param message description of the payload defect
     * @param cause   the parser failure, when the body was unparseable
     */
    public MalformedResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}