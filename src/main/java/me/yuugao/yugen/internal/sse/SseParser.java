package me.yuugao.yugen.internal.sse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;

import me.yuugao.yugen.exception.NetworkException;

/**
 * Incremental parser for the Server-Sent Events wire format.
 *
 * <p>Implements the subset the chat endpoints actually use: every event is
 * a {@code data:} line, events are separated by blank lines. The full SSE
 * machinery is deliberately trimmed:
 * <ul>
 *   <li>{@code event:}, {@code id:}, {@code retry:} fields and comment
 *       lines ({@code :keep-alive}) are parsed and ignored;</li>
 *   <li>several {@code data:} lines in one event are joined with a
 *       newline, as the specification requires;</li>
 *   <li>line terminators {@code \r\n}, {@code \n} and a lone {@code \r}
 *       are all accepted;</li>
 *   <li>a leading BOM is tolerated;</li>
 *   <li>data pending at end of input is dispatched instead of dropped, so
 *       endpoints that omit the final blank line still deliver their last
 *       event.</li>
 * </ul>
 *
 * <p>Reading is pull-based: {@link #next()} blocks until a complete event
 * is available, which is exactly what a token iterator over a live
 * response needs. Read failures surface as {@link NetworkException}
 * because a broken stream is a transport-level failure.
 */
public final class SseParser {

    private static final char BOM = '\uFEFF';

    private final PushbackInputStream in;
    private final ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private String readyEvent;
    private boolean firstLine = true;
    private boolean done;

    /**
     * Creates a parser over the raw response body.
     *
     * @param stream byte stream of an SSE response, UTF-8 encoded
     */
    public SseParser(InputStream stream) {
        this.in = new PushbackInputStream(stream, 1);
    }

    /**
     * Returns the data payload of the next event.
     *
     * <p>{@code [DONE]}-style terminators are not interpreted here: they
     * are ordinary data events, and the caller decides what they mean.
     *
     * @return event payload, or {@code null} when the input is exhausted;
     *         repeated calls after the end keep returning {@code null}
     * @throws NetworkException when the underlying stream fails mid-read
     */
    public String next() {
        if (done) {
            return null;
        }
        try {
            while (readyEvent == null) {
                int b = in.read();
                if (b == -1) {
                    done = true;
                    finishAtEof();
                    break;
                }
                if (b == '\n') {
                    handleLine(endLine());
                    continue;
                }
                if (b == '\r') {
                    swallowOptionalLf();
                    handleLine(endLine());
                    continue;
                }
                lineBytes.write(b);
            }
            String event = readyEvent;
            readyEvent = null;
            return event;
        } catch (IOException e) {
            throw new NetworkException("SSE stream read failed: " + e.getMessage(), e);
        }
    }

    private void finishAtEof() throws IOException {
        if (lineBytes.size() > 0) {
            handleLine(endLine());
        }
        if (readyEvent == null && !data.isEmpty()) {
            readyEvent = data.toString();
            data.setLength(0);
        }
    }

    private String endLine() {
        String text = lineBytes.toString(StandardCharsets.UTF_8);
        lineBytes.reset();
        return text;
    }

    private void handleLine(String text) {
        if (firstLine) {
            firstLine = false;
            if (!text.isEmpty() && text.charAt(0) == BOM) {
                text = text.substring(1);
            }
        }
        if (text.isEmpty()) {
            if (!data.isEmpty()) {
                readyEvent = data.toString();
                data.setLength(0);
            }
            return;
        }
        if (text.startsWith("data:")) {
            String value = text.substring("data:".length());
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (!data.isEmpty()) {
                data.append('\n');
            }
            data.append(value);
        }
    }

    private void swallowOptionalLf() throws IOException {
        int following = in.read();
        if (following != '\n' && following != -1) {
            in.unread(following);
        }
    }
}