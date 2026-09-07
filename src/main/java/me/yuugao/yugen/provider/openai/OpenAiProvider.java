package me.yuugao.yugen.provider.openai;

import me.yuugao.yugen.chat.*;
import me.yuugao.yugen.exception.*;
import me.yuugao.yugen.internal.json.Json;
import me.yuugao.yugen.internal.json.Json.JsonParseException;
import me.yuugao.yugen.internal.sse.SseParser;
import me.yuugao.yugen.provider.LlmProvider;
import me.yuugao.yugen.retry.RetryPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Client for the OpenAI chat-completions protocol.
 *
 * <p>The base URL is configurable, which makes this single implementation
 * cover every OpenAI-compatible endpoint: OpenAI itself, Ollama
 * ({@code http://localhost:11434/v1}), OpenRouter, DeepSeek, Groq, Together,
 * vLLM and LM Studio. Differences between these endpoints amount to a base
 * URL, a model name and an API key.
 *
 * <p>Response mapping is defensive: fields are read with type checks and
 * absent or wrongly-typed values degrade to documented defaults instead of
 * throwing, because providers differ in what they actually return. Only a
 * missing or empty {@code choices} list is treated as a hard
 * {@link MalformedResponseException}.
 *
 * <p>Streaming: {@link #chatStream(ChatRequest)} speaks the same protocol
 * with {@code "stream": true} and consumes the Server-Sent Events body
 * incrementally. Streaming calls are not retried: tokens may already have
 * been delivered, and replaying them would duplicate visible output.
 */
public final class OpenAiProvider implements LlmProvider {
    /**
     * Default endpoint: the official OpenAI API.
     */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private static final String DONE = "[DONE]";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;
    private final boolean ownsHttpClient;
    private final RetryPolicy retry;
    private final Duration requestTimeout;

    /**
     * Creates a client for the official OpenAI endpoint with no retries.
     *
     * @param apiKey OpenAI API key
     */
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

    /**
     * Starts building a configured client.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts building a client for the given API key.
     *
     * @param apiKey API key sent as a bearer token
     * @return a fresh builder with the key set
     */
    public static Builder builder(String apiKey) {
        return builder().apiKey(apiKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "openai";
    }

    /**
     * Performs one synchronous chat call, wrapped in the configured
     * {@link RetryPolicy}.
     *
     * @param request conversation and parameters to send
     * @return the completed exchange
     * @throws RateLimitException         on HTTP 429, after retries
     * @throws ProviderException          on other HTTP errors, after retries
     * @throws NetworkException           on transport failures, after retries
     * @throws MalformedResponseException when a 2xx payload cannot be
     *                                    interpreted
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        return retry.execute(() -> doChat(request));
    }

    /**
     * Streams the response token by token over the same endpoint.
     *
     * <p>Sends {@code "stream": true} and parses the Server-Sent Events
     * body as it arrives. The {@code requestTimeout} covers time until the
     * response headers arrive, not the whole stream: generation may take
     * as long as the provider keeps the connection open.
     *
     * <p>Not retried, unlike the sync path: after tokens have been
     * delivered, a transparent retry would duplicate them in the consumer's
     * output. Connection and payload failures surface from the stream
     * iteration itself as the usual typed exceptions.
     *
     * @param request conversation and parameters to send
     * @return live stream over the response
     * @throws RateLimitException on HTTP 429
     * @throws ProviderException  on other HTTP errors
     * @throws NetworkException   on transport failures
     */
    @Override
    public ChatStream chatStream(ChatRequest request) {
        Map<String, Object> wire = toWireFormat(request);
        wire.put("stream", true);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(wire)))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new NetworkException("Request to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YugenException("Request interrupted", e);
        }

        int status = response.statusCode();
        if (status >= 400) {
            String body = readErrorBody(response);
            if (status == 429) {
                throw new RateLimitException(retryAfterSeconds(response.headers()), "Rate limited by " + name());
            }
            throw new ProviderException(status, name() + " responded " + status + ": " + body);
        }

        InputStream bodyStream = response.body();
        return new ChatStream(
                new ChunkEventIterator(new SseParser(bodyStream), request.model()),
                bodyStream);
    }

    /**
     * Releases the HTTP client when this provider created it. A client
     * injected through {@link Builder#httpClient(HttpClient)} remains the
     * caller's responsibility.
     */
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
            throw new RateLimitException(retryAfterSeconds(response.headers()), "Rate limited by " + name());
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
        } catch (JsonParseException e) {
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

    private static Long retryAfterSeconds(HttpHeaders headers) {
        String header = headers.firstValue("Retry-After").orElse(null);
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readErrorBody(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Adapts the SSE event stream into domain events, one chunk at a time.
     *
     * <p>After a read or parse failure the iterator keeps rethrowing the
     * recorded exception, so both further iteration and
     * {@code ChatStream.await()} fail deterministically instead of silently
     * resuming a broken connection.
     */
    private final class ChunkEventIterator implements Iterator<ChatStream.Event> {
        private final SseParser sse;
        private final String fallbackModel;
        private ChatStream.Event next;
        private boolean exhausted;
        private RuntimeException failure;

        ChunkEventIterator(SseParser sse, String fallbackModel) {
            this.sse = sse;
            this.fallbackModel = fallbackModel;
        }

        @Override
        public boolean hasNext() {
            if (failure != null) {
                throw failure;
            }
            if (next != null) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            try {
                next = readNext();
            } catch (RuntimeException e) {
                failure = e;
                throw e;
            }
            return next != null;
        }

        @Override
        public ChatStream.Event next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            ChatStream.Event event = next;
            next = null;
            return event;
        }

        private ChatStream.Event readNext() {
            String data = sse.next();
            if (data == null) {
                exhausted = true;
                return null;
            }
            if (DONE.equals(data.trim())) {
                exhausted = true;
                return null;
            }
            return toEvent(data);
        }

        private ChatStream.Event toEvent(String data) {
            Object parsed;
            try {
                parsed = Json.parse(data);
            } catch (JsonParseException e) {
                throw new MalformedResponseException("Unparseable stream chunk from " + name(), e);
            }
            Map<?, ?> root = asMap(parsed);
            if (root == null) {
                throw new MalformedResponseException("Unexpected stream chunk shape from " + name());
            }

            String delta = null;
            FinishReason finishReason = null;
            List<?> choices = asList(root.get("choices"));
            if (choices != null && !choices.isEmpty()) {
                Map<?, ?> choice = asMap(choices.getFirst());
                if (choice != null) {
                    Map<?, ?> deltaMap = asMap(choice.get("delta"));
                    if (deltaMap != null) {
                        delta = asString(deltaMap.get("content"));
                    }
                    String wireFinish = asString(choice.get("finish_reason"));
                    if (wireFinish != null) {
                        finishReason = FinishReason.fromWire(wireFinish);
                    }
                }
            }

            Map<?, ?> usageMap = asMap(root.get("usage"));
            Usage usage = usageMap == null
                    ? null
                    : new Usage(asLong(usageMap.get("prompt_tokens")), asLong(usageMap.get("completion_tokens")));

            String model = asString(root.get("model"));
            if (model == null) {
                model = fallbackModel;
            }
            return new ChatStream.Event(delta, model, finishReason, usage);
        }
    }

    /**
     * Builder for {@link OpenAiProvider}.
     *
     * <p>All values except the API key have defaults. An injected
     * {@link HttpClient} is not closed by {@link OpenAiProvider#close()}.
     */
    public static final class Builder {
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private HttpClient http;
        private RetryPolicy retry = RetryPolicy.NONE;
        private Duration requestTimeout = Duration.ofSeconds(60);

        /**
         * Creates a builder with default endpoint, timeout and no retries.
         */
        public Builder() {
        }

        /**
         * Sets the API key sent as a bearer token.
         *
         * @param apiKey provider API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Points the client at an OpenAI-compatible endpoint.
         *
         * @param baseUrl endpoint root up to and including {@code /v1};
         *                a trailing slash is tolerated
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Injects a custom HTTP client, e.g. one with a shared connection
         * pool or a proxy.
         *
         * @param http client to use; ownership stays with the caller
         * @return this builder
         */
        public Builder httpClient(HttpClient http) {
            this.http = http;
            return this;
        }

        /**
         * Sets the retry strategy for transient failures.
         *
         * @param retry policy to apply around each call
         * @return this builder
         */
        public Builder retry(RetryPolicy retry) {
            this.retry = retry;
            return this;
        }

        /**
         * Sets the per-request timeout covering the full response.
         *
         * @param timeout maximum time for one attempt
         * @return this builder
         */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        /**
         * Builds the provider after validating required fields.
         *
         * @return a ready-to-use provider
         * @throws IllegalStateException if the API key is missing
         */
        public OpenAiProvider build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("apiKey is required");
            }
            return new OpenAiProvider(this);
        }
    }
}
