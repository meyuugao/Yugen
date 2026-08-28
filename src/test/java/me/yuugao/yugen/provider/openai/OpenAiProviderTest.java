package me.yuugao.yugen.provider.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import me.yuugao.yugen.chat.ChatRequest;
import me.yuugao.yugen.chat.ChatResponse;
import me.yuugao.yugen.chat.FinishReason;
import me.yuugao.yugen.exception.NetworkException;
import me.yuugao.yugen.exception.ProviderException;
import me.yuugao.yugen.exception.RateLimitException;
import me.yuugao.yugen.internal.json.Json;
import me.yuugao.yugen.retry.RetryPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against an embedded {@code com.sun.net.httpserver.HttpServer}:
 * no WireMock, no network, no API keys. The provider client is pinned to
 * HTTP/1.1 because the JDK test server does not speak h2c upgrade.
 */
class OpenAiProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();
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

    private void respondWith(int status, String body) {
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, status, body);
        });
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static OpenAiProvider provider(String baseUrl, RetryPolicy retry) {
        return OpenAiProvider.builder()
                .apiKey("test-key")
                .baseUrl(baseUrl)
                .retry(retry)
                .httpClient(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build())
                .build();
    }

    private static ChatRequest request() {
        return ChatRequest.builder()
                .model("gpt-4o-mini")
                .system("be terse")
                .user("hello")
                .temperature(0.2)
                .build();
    }

    @Test
    void mapsSuccessfulResponse() {
        respondWith(200, """
                {
                  "id": "chatcmpl-1",
                  "model": "gpt-4o-mini-2024-07-18",
                  "choices": [
                    {
                      "index": 0,
                      "message": {"role": "assistant", "content": "hi there"},
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
                }
                """);

        ChatResponse response = provider(baseUrl, RetryPolicy.none()).chat(request());

        assertThat(response.content()).isEqualTo("hi there");
        assertThat(response.model()).isEqualTo("gpt-4o-mini-2024-07-18");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage().inputTokens()).isEqualTo(11);
        assertThat(response.usage().outputTokens()).isEqualTo(7);
        assertThat(response.usage().totalTokens()).isEqualTo(18);
    }

    @Test
    void toleratesMissingUsageAndFinishReason() {
        respondWith(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"model\":\"m\"}");

        ChatResponse response = provider(baseUrl, RetryPolicy.none()).chat(request());

        assertThat(response.content()).isEqualTo("ok");
        assertThat(response.finishReason()).isEqualTo(FinishReason.OTHER);
        assertThat(response.usage()).isEqualTo(new me.yuugao.yugen.chat.Usage(0, 0));
    }

    @Test
    void sendsOpenAiWireFormat() {
        respondWith(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"model\":\"m\"}");

        provider(baseUrl, RetryPolicy.none()).chat(request());

        assertThat(receivedAuthHeaders).containsExactly("Bearer test-key");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) Json.parse(receivedBodies.get(0));
        assertThat(body.get("model")).isEqualTo("gpt-4o-mini");
        assertThat(body.get("temperature")).isEqualTo(0.2);
        assertThat(body).doesNotContainKey("max_tokens");

        List<?> messages = (List<?>) body.get("messages");
        assertThat(messages).hasSize(2);
        assertThat((Map<String, Object>) messages.get(0))
                .containsEntry("role", "system")
                .containsEntry("content", "be terse");
        assertThat((Map<String, Object>) messages.get(1))
                .containsEntry("role", "user")
                .containsEntry("content", "hello");
    }

    @Test
    void retriesRateLimitThenSucceeds() {
        server.createContext("/v1/chat/completions", exchange -> {
            int n = hits.incrementAndGet();
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (n == 1) {
                respond(exchange, 429, "{\"error\":{\"message\":\"slow down\"}}");
            } else {
                respond(exchange, 200,
                        "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                                + "\"model\":\"m\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
            }
        });

        ChatResponse response = provider(baseUrl, new RetryPolicy(3, 0, 2.0, 0)).chat(request());

        assertThat(response.content()).isEqualTo("ok");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void rateLimitExhaustionThrowsRateLimitException() {
        respondWith(429, "{\"error\":{\"message\":\"slow down\"}}");

        assertThatThrownBy(() -> provider(baseUrl, new RetryPolicy(2, 0, 2.0, 0)).chat(request()))
                .isInstanceOf(RateLimitException.class);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void clientErrorsAreNotRetried() {
        respondWith(400, "{\"error\":{\"message\":\"bad request\"}}");

        assertThatThrownBy(() -> provider(baseUrl, new RetryPolicy(3, 0, 2.0, 0)).chat(request()))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("400");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void emptyChoicesFailWithProviderException() {
        respondWith(200, "{\"choices\":[],\"model\":\"m\"}");

        assertThatThrownBy(() -> provider(baseUrl, RetryPolicy.none()).chat(request()))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("No choices");
    }

    @Test
    void networkFailuresAreWrapped() {
        server.stop(0);
        String deadUrl = baseUrl;

        assertThatThrownBy(() -> provider(deadUrl, RetryPolicy.none()).chat(request()))
                .isInstanceOf(NetworkException.class);
    }
}
