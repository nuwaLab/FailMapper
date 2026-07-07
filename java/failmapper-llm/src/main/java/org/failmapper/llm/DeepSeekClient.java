package org.failmapper.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI-compatible DeepSeek chat-completions client.
 *
 * Defaults mirror the Python baseline (feedback.py DeepSeek settings):
 * temperature 0.7, max_tokens 8192, 3 retries with linear backoff, 120s timeout.
 * The API key is taken from the DEEPSEEK_API_KEY environment variable and is
 * never persisted anywhere.
 */
public final class DeepSeekClient implements LlmClient {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-v4-pro";

    private static final int MAX_RETRIES = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    /**
     * Observability (M4 acceptance): when the {@code FM_LLM_VERBOSE} environment
     * variable is set, every {@link #complete} call logs one start line and one
     * outcome line to stderr, tagged with a process-wide call number. Off by
     * default; never logs prompt or completion content, only sizes.
     */
    private static final boolean VERBOSE = System.getenv("FM_LLM_VERBOSE") != null;
    private static final java.util.concurrent.atomic.AtomicLong CALLS =
            new java.util.concurrent.atomic.AtomicLong();

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final double temperature;
    private final int maxTokens;

    public DeepSeekClient() {
        this(DEFAULT_BASE_URL, envModelOrDefault(), requireEnvKey(), 0.7, 8192);
    }

    public DeepSeekClient(String baseUrl, String model, String apiKey, double temperature, int maxTokens) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    private static String requireEnvKey() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            throw new LlmException("DEEPSEEK_API_KEY environment variable is not set");
        }
        return key;
    }

    private static String envModelOrDefault() {
        String model = System.getenv("DEEPSEEK_MODEL");
        return model == null || model.isBlank() ? DEFAULT_MODEL : model;
    }

    public String model() {
        return model;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        long callNo = CALLS.incrementAndGet();
        long startNanos = System.nanoTime();
        if (VERBOSE) {
            System.err.printf(java.util.Locale.ROOT, "[llm] call #%d model=%s prompt=%d chars%n",
                    callNo, model, userPrompt == null ? 0 : userPrompt.length());
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new LlmException("failed to build request", e);
        }

        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    JsonNode content = root.path("choices").path(0).path("message").path("content");
                    if (content.isTextual()) {
                        if (VERBOSE) {
                            System.err.printf(java.util.Locale.ROOT,
                                    "[llm] call #%d ok reply=%d chars elapsed=%.1fs%n",
                                    callNo, content.asText().length(),
                                    (System.nanoTime() - startNanos) / 1e9);
                        }
                        return content.asText();
                    }
                    throw new LlmException("unexpected response shape: " + abbreviate(response.body()));
                }
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    last = new LlmException("HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
                    Thread.sleep(2000L * attempt);
                    continue;
                }
                throw new LlmException("HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("interrupted", e);
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("interrupted", ie);
                }
            }
        }
        throw new LlmException("DeepSeek call failed after " + MAX_RETRIES + " attempts", last);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
