package me.yuugao.yugen.internal.sse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import me.yuugao.yugen.exception.NetworkException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseParserTest {
    private static SseParser parser(String text) {
        return new SseParser(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesSimpleDataEvents() {
        SseParser sse = parser("data: first\n\ndata: second\n\n");
        assertThat(sse.next()).isEqualTo("first");
        assertThat(sse.next()).isEqualTo("second");
        assertThat(sse.next()).isNull();
    }

    @Test
    void handlesCrlfAndLoneCrTerminators() {
        SseParser sse = parser("data: a\r\n\r\ndata: b\r\rdata: c\n\n");
        assertThat(sse.next()).isEqualTo("a");
        assertThat(sse.next()).isEqualTo("b");
        assertThat(sse.next()).isEqualTo("c");
        assertThat(sse.next()).isNull();
    }

    @Test
    void joinsMultipleDataLinesWithNewline() {
        SseParser sse = parser("data: line1\ndata: line2\n\n");
        assertThat(sse.next()).isEqualTo("line1\nline2");
    }

    @Test
    void stripsExactlyOneOptionalSpaceAfterColon() {
        SseParser sse = parser("data:nospace\n\ndata:  two\n\n");
        assertThat(sse.next()).isEqualTo("nospace");
        assertThat(sse.next()).isEqualTo(" two");
    }

    @Test
    void ignoresCommentsAndNonDataFields() {
        SseParser sse = parser(": keep-alive\nevent: message\nid: 7\nretry: 100\ndata: payload\n\n");
        assertThat(sse.next()).isEqualTo("payload");
        assertThat(sse.next()).isNull();
    }

    @Test
    void doneSentinelIsOrdinaryData() {
        SseParser sse = parser("data: [DONE]\n\n");
        assertThat(sse.next()).isEqualTo("[DONE]");
    }

    @Test
    void dispatchesPendingEventAtEof() {
        SseParser sse = parser("data: tail");
        assertThat(sse.next()).isEqualTo("tail");
        assertThat(sse.next()).isNull();
    }

    @Test
    void stripsLeadingBom() {
        String text = '\uFEFF' + "data: a\n\n";
        SseParser sse = parser(text);
        assertThat(sse.next()).isEqualTo("a");
    }

    @Test
    void emptyInputYieldsNullRepeatedly() {
        SseParser sse = parser("");
        assertThat(sse.next()).isNull();
        assertThat(sse.next()).isNull();
    }

    @Test
    void readFailureBecomesNetworkException() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };
        SseParser sse = new SseParser(failing);
        assertThatThrownBy(sse::next)
                .isInstanceOf(NetworkException.class)
                .hasMessageContaining("connection reset");
    }
}