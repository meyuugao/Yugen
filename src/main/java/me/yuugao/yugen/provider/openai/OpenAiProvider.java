package me.yuugao.yugen.provider.openai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.yuugao.yugen.chat.ChatRequest;
import me.yuugao.yugen.chat.ChatResponse;
import me.yuugao.yugen.chat.FinishReason;
import me.yuugao.yugen.chat.Message;
import me.yuugao.yugen.chat.Usage;
import me.yuugao.yugen.exception.*;
import me.yuugao.yugen.internal.json.Json;
import me.yuugao.yugen.provider.LlmProvider;
import me.yuugao.yugen.retry.RetryPolicy;

/**
 * Client for the OpenAI chat-completions protocol.
 *
 * <p>The base URL is configurable, which makes this one implementation cover
 * every OpenAI-compatible endpoint: OpenAI itself, Ollama ({@code /v1}),
 * OpenRouter, DeepSeek, Groq, Together, vLLM, LM Studio.
 */
public final class OpenAiProvider implements LlmProvider {
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;
    private final boolean ownsHttpClient;
    private final RetryPolicy retry;
    private final Duration requestTimeout;

    public OpenAiProvider(String apiKey) {
        this(builder().apiKey(apiKey));
    }

    private OpenAiProvider(Builder builder) {
        this.baseUrl = stripTrailingSlash(builder.baseUrl);
        this.apiKey = builder.apiKey;
        this.ownsHttpClient = builder.http == null;
        this.http = builder.http != null
                ? builder.http
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.retry = builder.retry;
        this.requestTimeout = builder.requestTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String apiKey) {
        return builder().apiKey(apiKey);
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return retry.execute(() -> doChat(request));
    }

    @Override
    public void close() {
        if (ownsHttpClient) {
            http.close();
        }
    }

    private ChatResponse doChat(ChatRequest request) {
        String body = Json.write(toWireFormat(request));
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new NetworkException("Request to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YugenException("Request interrupted", e);
        }

        int status = response.statusCode();
        if (status == 429) {
            throw new RateLimitException(retryAfterSeconds(response), "Rate limited by " + name());
        }
        if (status >= 400) {
            throw new ProviderException(status, name() + " responded " + status + ": " + response.body());
        }

        return fromWireFormat(response.body());
    }

    private Map<String, Object> toWireFormat(ChatRequest request) {
        List<Object> messages = new ArrayList<>();
        for (Message message : request.messages()) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", message.role());
            wire.put("content", message.content());
            messages.add(wire);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", request.model());
        root.put("messages", messages);
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            root.put("max_tokens", request.maxTokens());
        }
        return root;
    }

    private ChatResponse fromWireFormat(String body) {
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (Json.JsonParseException e) {
            throw new MalformedResponseException("Unparseable response body from " + name(), e);
        }
        Map<?, ?> root = asMap(parsed);
        if (root == null) {
            throw new MalformedResponseException("Unexpected response shape from " + name());
        }
        List<?> choices = asList(root.get("choices"));
        if (choices == null || choices.isEmpty()) {
            throw new MalformedResponseException("No choices in response from " + name());
        }
        Map<?, ?> choice = asMap(choices.getFirst());
        Map<?, ?> message = choice == null ? null : asMap(choice.get("message"));
        Map<?, ?> usageMap = asMap(root.get("usage"));

        String content = message == null ? null : asString(message.get("content"));
        String model = asString(root.get("model"));
        FinishReason finishReason = FinishReason.fromWire(choice == null ? null : asString(choice.get("finish_reason")));
        Usage usage = usageMap == null
                ? new Usage(0, 0)
                : new Usage(asLong(usageMap.get("prompt_tokens")), asLong(usageMap.get("completion_tokens")));
        return new ChatResponse(content, model, finishReason, usage);
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static Long retryAfterSeconds(HttpResponse<String> response) {
        String header = response.headers().firstValue("Retry-After").orElse(null);
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static final class Builder {
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private HttpClient http;
        private RetryPolicy retry = RetryPolicy.NONE;
        private Duration requestTimeout = Duration.ofSeconds(60);

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder httpClient(HttpClient http) {
            this.http = http;
            return this;
        }

        public Builder retry(RetryPolicy retry) {
            this.retry = retry;
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        public OpenAiProvider build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("apiKey is required");
            }
            return new OpenAiProvider(this);
        }
    }
}