package me.yuugao.yugen.provider.openai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

import me.yuugao.yugen.chat.ChatRequest;
import me.yuugao.yugen.chat.ChatResponse;
import me.yuugao.yugen.chat.ChatStream;
import me.yuugao.yugen.chat.FinishReason;
import me.yuugao.yugen.chat.Usage;
import me.yuugao.yugen.exception.MalformedResponseException;
import me.yuugao.yugen.exception.RateLimitException;
import me.yuugao.yugen.internal.json.Json;
import me.yuugao.yugen.retry.RetryPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against an embedded {@code com.sun.net.httpserver.HttpServer} writing
 * Server-Sent Events incrementally: no network, no API keys. The client is
 * pinned to HTTP/1.1 because the JDK test server does not speak h2c.
 */
class OpenAiProviderStreamingTest {
    private HttpServer server;
    private String baseUrl;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void streamHandler(ThrowingConsumer<OutputStream> script) {
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                script.accept(out);
            } catch (Exception e) {
                // handler script controlled by the test
            }
            exchange.close();
        });
    }

    private static void sse(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String chunk(String content, String finishReason) {
        String delta = content == null ? "{}" : "{\"content\":" + Json.write(content) + "}";
        String finish = finishReason == null ? "" : ",\"finish_reason\":" + Json.write(finishReason);
        return "{\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":" + delta + finish + "}],\"usage\":null}";
    }

    private static String usageChunk() {
        return "{\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}";
    }

    private OpenAiProvider provider() {
        return OpenAiProvider.builder()
                .apiKey("test-key")
                .baseUrl(baseUrl)
                .retry(new RetryPolicy(3, 0, 2.0, 0))
                .httpClient(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build())
                .build();
    }

    private static ChatRequest request() {
        return ChatRequest.builder()
                .model("gpt-4o-mini")
                .system("be terse")
                .user("hello")
                .build();
    }

    private static List<String> drain(ChatStream stream) {
        List<String> tokens = new ArrayList<>();
        stream.forEach(tokens::add);
        return tokens;
    }

    @Test
    void streamsTokensAndAggregates() {
        streamHandler(out -> {
            sse(out, chunk(null, null));
            sse(out, chunk("Hel", null));
            sse(out, chunk("lo ", null));
            sse(out, chunk("world", null));
            sse(out, chunk(null, "stop"));
            sse(out, usageChunk());
            sse(out, "[DONE]");
        });

        try (ChatStream stream = provider().chatStream(request())) {
            List<String> tokens = drain(stream);
            ChatResponse response = stream.await();

            assertThat(tokens).containsExactly("Hel", "lo ", "world");
            assertThat(response.content()).isEqualTo("Hello world");
            assertThat(response.model()).isEqualTo("gpt-4o-mini-2024-07-18");
            assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
            assertThat(response.usage().totalTokens()).isEqualTo(18);
        }

        String body = receivedBodies.getFirst();
        assertThat((Map<String, Object>) Json.parse(body))
                .containsEntry("stream", true)
                .containsEntry("model", "gpt-4o-mini");
    }

    @Test
    @Timeout(10)
    void deliversTokensAsTheyArrive() {
        CountDownLatch firstTokenConsumed = new CountDownLatch(1);
        streamHandler(out -> {
            sse(out, chunk(null, null));
            sse(out, chunk("first", null));
            // hold the connection until the consumer proves it saw the token
            if (!firstTokenConsumed.await(30, TimeUnit.SECONDS)) {
                return;
            }
            sse(out, chunk("second", null));
            sse(out, chunk(null, "stop"));
            sse(out, "[DONE]");
        });

        try (ChatStream stream = provider().chatStream(request())) {
            List<String> tokens = new ArrayList<>();
            for (String token : stream) {
                tokens.add(token);
                firstTokenConsumed.countDown();
            }
            assertThat(tokens).containsExactly("first", "second");
        }
    }

    @Test
    void toleratesMissingDoneSentinel() {
        streamHandler(out -> {
            sse(out, chunk("Hel", null));
            sse(out, chunk("lo", null));
            sse(out, chunk(null, "stop"));
        });

        try (ChatStream stream = provider().chatStream(request())) {
            assertThat(drain(stream)).containsExactly("Hel", "lo");
            assertThat(stream.await().content()).isEqualTo("Hello");
            assertThat(stream.await().finishReason()).isEqualTo(FinishReason.STOP);
        }
    }

    @Test
    void missingUsageAndFinishReasonDegradeGracefully() {
        streamHandler(out -> {
            sse(out, chunk("hi", null));
            sse(out, "[DONE]");
        });

        try (ChatStream stream = provider().chatStream(request())) {
            assertThat(drain(stream)).containsExactly("hi");
            ChatResponse response = stream.await();
            assertThat(response.finishReason()).isEqualTo(FinishReason.OTHER);
            assertThat(response.usage()).isEqualTo(new Usage(0, 0));
        }
    }

    @Test
    void errorStatusSurfacesImmediatelyWithoutRetry() {
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertThatThrownBy(() -> provider().chatStream(request()))
                .isInstanceOf(RateLimitException.class);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void unparseableChunkFailsAsMalformedResponse() {
        streamHandler(out -> {
            sse(out, chunk("Hel", null));
            sse(out, "{broken json");
        });

        try (ChatStream stream = provider().chatStream(request())) {
            assertThatThrownBy(() -> drain(stream))
                    .isInstanceOf(MalformedResponseException.class)
                    .hasMessageContaining("Unparseable stream chunk");
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}