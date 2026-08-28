package me.yuugao.yugen.exception;

/** Base type for everything this library throws deliberately. */
public class YugenException extends RuntimeException {

    public YugenException(String message) {
        super(message);
    }

    public YugenException(String message, Throwable cause) {
        super(message, cause);
    }
}
