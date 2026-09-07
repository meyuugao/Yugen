package me.yuugao.yugen.chat;

import java.util.Iterator;
import java.util.NoSuchElementException;

import me.yuugao.yugen.exception.YugenException;

/**
 * A live streaming chat response, consumed as an {@code Iterable} of token
 * strings and completed by {@link #await()}.
 *
 * <p>Streaming lets applications show text as it is generated instead of
 * waiting for the whole answer. This class exposes the pull model: each
 * {@code hasNext()} blocks until the provider delivers the next token, so
 * ordinary loops and {@code forEach} drive the stream without callbacks.
 *
 * <pre>{@code
 * try (ChatStream stream = yugen.chatStream("Tell me a haiku about the sea.")) {
 *     stream.forEach(System.out::print);          // tokens as they arrive
 *     ChatResponse full = stream.await();         // aggregate of the same stream
 *     System.out.println(full.usage().totalTokens() + " tokens");
 * }
 * }</pre>
 *
 * <p>Lifecycle rules:
 * <ul>
 *   <li>single use: one {@code iterator()} per stream; {@code await()}
 *       consumes the stream if it was not iterated yet;</li>
 *   <li>{@code await()} after natural exhaustion returns the aggregated
 *       {@link ChatResponse}: joined content, reported model, finish reason
 *       and usage. Absent values degrade the same way as in the sync path
 *       (finish reason {@code OTHER}, usage zero);</li>
 *   <li>{@code close()} aborts a stream that is no longer needed and
 *       releases the underlying connection; after an early close,
 *       {@code await()} refuses to invent a partial response;</li>
 *   <li>failures surface from the iteration itself (or from
 *       {@code await()}) as the same typed exceptions as the sync path.</li>
 * </ul>
 *
 * <p>Instances are not thread-safe: a stream has exactly one consumer.
 * Applications that need the partial text after a mid-stream failure should
 * accumulate tokens themselves while iterating, because the aggregate is
 * only produced for streams that run to completion.
 *
 * <p>Constructed by provider implementations from a source of
 * {@link Event}s plus a closeable resource; application code only consumes.
 */
public final class ChatStream implements Iterable<String>, AutoCloseable {

    private final Iterator<Event> events;
    private final AutoCloseable resource;

    private final StringBuilder content = new StringBuilder();
    private String model;
    private FinishReason finishReason;
    private Usage usage;
    private boolean drained;
    private boolean iteratorIssued;
    private boolean closed;
    private ChatResponse aggregate;

    /**
     * Creates a stream over the given event source.
     *
     * @param events   provider-translated stream events, consumed lazily
     * @param resource connection-level resource released by {@link #close()};
     *                 may be a no-op for synthetic sources
     */
    public ChatStream(Iterator<Event> events, AutoCloseable resource) {
        this.events = events;
        this.resource = resource;
    }

    /**
     * Returns the token iterator for this stream.
     *
     * <p>Only one iterator is ever issued: the stream is a live connection,
     * not a replayable collection. Events without a text delta (usage
     * accounting, final status) are consumed internally and skipped.
     *
     * @return iterator of token strings in arrival order
     * @throws IllegalStateException if the stream was already iterated,
     *         awaited or closed
     */
    @Override
    public Iterator<String> iterator() {
        if (iteratorIssued || drained || closed) {
            throw new IllegalStateException("Stream is single-use and was already consumed or closed");
        }
        iteratorIssued = true;
        return new Iterator<>() {
            private String pending;

            @Override
            public boolean hasNext() {
                if (pending != null) {
                    return true;
                }
                while (pending == null) {
                    if (!events.hasNext()) {
                        finish();
                        return false;
                    }
                    Event event = events.next();
                    absorb(event);
                    if (event.delta() != null) {
                        pending = event.delta();
                    }
                }
                return true;
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                String token = pending;
                pending = null;
                return token;
            }
        };
    }

    /**
     * Waits for the stream to complete and returns the aggregate response.
     *
     * <p>When called before iteration, the remaining events are consumed
     * internally (tokens are discarded); when called after a completed
     * iteration, the aggregate is returned immediately. When called on a
     * stream whose iteration already failed, the recorded failure is
     * rethrown.
     *
     * @return aggregated response for the whole stream
     * @throws IllegalStateException if the stream was closed before
     *         completion
     * @throws me.yuugao.yugen.exception.YugenException subtypes for
     *         transport or payload failures that ended the stream
     */
    public ChatResponse await() {
        if (closed && !drained) {
            throw new IllegalStateException("Stream was closed before completion");
        }
        if (!drained) {
            while (events.hasNext()) {
                absorb(events.next());
            }
            finish();
        }
        return aggregate;
    }

    /**
     * Aborts the stream (if still running) and releases the underlying
     * connection. Idempotent; safe after natural exhaustion.
     *
     * @throws YugenException if releasing the resource fails
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            resource.close();
        } catch (Exception e) {
            throw new YugenException("Failed to release stream resources", e);
        }
    }

    private void absorb(Event event) {
        if (event.delta() != null) {
            content.append(event.delta());
        }
        if (event.model() != null && model == null) {
            model = event.model();
        }
        if (event.finishReason() != null) {
            finishReason = event.finishReason();
        }
        if (event.usage() != null) {
            usage = event.usage();
        }
    }

    private void finish() {
        if (drained) {
            return;
        }
        drained = true;
        aggregate = new ChatResponse(
                content.toString(),
                model,
                finishReason != null ? finishReason : FinishReason.OTHER,
                usage != null ? usage : new Usage(0, 0));
    }

    /**
     * One incremental piece of a streaming response as reported by the
     * provider.
     *
     * <p>Every component is nullable: a chunk carries a component only when
     * the provider sent it. Deltas are text fragments; the model identifier
     * repeats in most chunks; the finish reason appears once near the end;
     * usage, when the provider reports it at all, arrives in a trailing
     * chunk after the finish reason.
     *
     * @param delta        text fragment of this chunk, absent for metadata
     *                     chunks
     * @param model        model identifier as reported by the provider
     * @param finishReason normalized stop reason, absent until known
     * @param usage        token accounting, absent until reported
     */
    public record Event(String delta, String model, FinishReason finishReason, Usage usage) {
    }
}