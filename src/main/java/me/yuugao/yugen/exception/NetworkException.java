package me.yuugao.yugen.exception;

/** Transport-level failure: connection refused, timeout, DNS, reset. */
public class NetworkException extends YugenException {

    public NetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
