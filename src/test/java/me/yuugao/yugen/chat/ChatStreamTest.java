package me.yuugao.yugen.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

import me.yuugao.yugen.exception.NetworkException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatStreamTest {
    private static ChatStream streamOf(ChatStream.Event... events) {
        return new ChatStream(Arrays.asList(events).iterator(), () -> {});
    }

    private static ChatStream.Event ev(String delta, String model, FinishReason finishReason, Usage usage) {
        return new ChatStream.Event(delta, model, finishReason, usage);
    }

    @Test
    void iteratesTokensSkippingMetadataEvents() {
        ChatStream stream = streamOf(
                ev(null, "m", null, null),
                ev("Hel", "m", null, null),
                ev("lo ", null, null, null),
                ev(null, null, FinishReason.STOP, null),
                ev(null, null, null, new Usage(5, 9)));

        List<String> tokens = new ArrayList<>();
        stream.forEach(tokens::add);

        assertThat(tokens).containsExactly("Hel", "lo ");
    }

    @Test
    void awaitAfterIterationAggregatesTheResponse() {
        ChatStream stream = streamOf(
                ev("Hel", "gpt-4o-mini-2024", null, null),
                ev("lo", "gpt-4o-mini-2024", null, null),
                ev("!", null, FinishReason.LENGTH, null),
                ev(null, null, null, new Usage(5, 9)));

        List<String> tokens = new ArrayList<>();
        stream.forEach(tokens::add);
        ChatResponse response = stream.await();

        assertThat(tokens).containsExactly("Hel", "lo", "!");
        assertThat(response.content()).isEqualTo("Hello!");
        assertThat(response.model()).isEqualTo("gpt-4o-mini-2024");
        assertThat(response.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(response.usage().totalTokens()).isEqualTo(14);
    }

    @Test
    void awaitWithoutIterationDrainsAndReturnsAggregate() {
        ChatStream stream = streamOf(
                ev("Hel", "m", null, null),
                ev("lo", null, FinishReason.STOP, new Usage(1, 2)));

        ChatResponse response = stream.await();

        assertThat(response.content()).isEqualTo("Hello");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThatThrownBy(stream::iterator)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void absentFinishReasonAndUsageDegradeLikeSyncPath() {
        ChatStream stream = streamOf(ev("hi", "m", null, null));

        ChatResponse response = stream.await();

        assertThat(response.finishReason()).isEqualTo(FinishReason.OTHER);
        assertThat(response.usage()).isEqualTo(new Usage(0, 0));
    }

    @Test
    void laterUsageWinsOverEarlierOne() {
        ChatStream stream = streamOf(
                ev("a", "m", null, new Usage(1, 1)),
                ev(null, null, FinishReason.STOP, new Usage(5, 9)));

        assertThat(stream.await().usage()).isEqualTo(new Usage(5, 9));
    }

    @Test
    void streamIsSingleUseForIteration() {
        ChatStream stream = streamOf(ev("a", "m", null, null));

        stream.iterator();
        assertThatThrownBy(stream::iterator)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void awaitIsIdempotent() {
        ChatStream stream = streamOf(ev("a", "m", null, null));

        ChatResponse first = stream.await();
        ChatResponse second = stream.await();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void closeBeforeCompletionRefusesToInventAResponse() {
        ChatStream stream = streamOf(ev("a", "m", null, null));

        stream.close();
        stream.close();

        assertThatThrownBy(stream::await)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed before completion");
    }

    @Test
    void closeAfterExhaustionKeepsTheAggregate() {
        ChatStream stream = streamOf(ev("a", "m", FinishReason.STOP, new Usage(1, 1)));

        stream.await();
        stream.close();

        assertThat(stream.await().content()).isEqualTo("a");
    }

    @Test
    void iteratorFailurePropagatesAndAwaitRethrows() {
        NetworkException boom = new NetworkException("reset", null);
        Iterator<ChatStream.Event> failing = new Iterator<>() {
            private int served;

            @Override
            public boolean hasNext() {
                if (served >= 2) {
                    throw boom;
                }
                return true;
            }

            @Override
            public ChatStream.Event next() {
                served++;
                return ev("tok" + served, "m", null, null);
            }
        };
        ChatStream stream = new ChatStream(failing, () -> {});

        List<String> tokens = new ArrayList<>();
        assertThatThrownBy(() -> stream.forEach(tokens::add))
                .isInstanceOf(NetworkException.class);
        assertThat(tokens).containsExactly("tok1", "tok2");

        assertThatThrownBy(stream::await)
                .isInstanceOf(NetworkException.class);
    }
}