package com.meterian.factory.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StreamEventParser {

    private static final String NO_RESULT_TEXT = "The stream has no result line.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;
    private final List<UsageWindow> usageWindows = new ArrayList<>();
    private Optional<String> apiKeySource = Optional.empty();
    private Optional<Long> tokensInUse = Optional.empty();
    private Optional<JsonNode> result = Optional.empty();

    public StreamEventParser(Clock clock) {
        this.clock = clock;
    }

    public void accept(String line) {
        readJson(line).ifPresent(this::handleEvent);
    }

    public Optional<String> apiKeySource() {
        return apiKeySource;
    }

    public SessionReport report() {
        return result.map(this::buildReport).orElseGet(this::buildMissingResultReport);
    }

    private Optional<JsonNode> readJson(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    private void handleEvent(JsonNode event) {
        switch (event.path("type").asText()) {
            case "system" -> handleSystemEvent(event);
            case "assistant" -> handleAssistantEvent(event);
            case "rate_limit_event" -> handleRateLimitEvent(event);
            case "result" -> result = Optional.of(event);
            default -> { }
        }
    }

    private void handleSystemEvent(JsonNode event) {
        if ("init".equals(event.path("subtype").asText()) && event.hasNonNull("apiKeySource")) {
            apiKeySource = Optional.of(event.get("apiKeySource").asText());
        }
    }

    private void handleAssistantEvent(JsonNode event) {
        JsonNode usage = event.path("message").path("usage");
        if (usage.isObject()) {
            tokensInUse = Optional.of(usage.path("input_tokens").asLong()
                    + usage.path("cache_read_input_tokens").asLong()
                    + usage.path("cache_creation_input_tokens").asLong());
        }
    }

    private void handleRateLimitEvent(JsonNode event) {
        Instant reportedAt = clock.instant();
        Iterator<Map.Entry<String, JsonNode>> windows = event.path("rate_limit_info").path("unifiedWindows").fields();
        while (windows.hasNext()) {
            Map.Entry<String, JsonNode> window = windows.next();
            usageWindows.add(buildUsageWindow(window.getKey(), window.getValue(), reportedAt));
        }
    }

    private UsageWindow buildUsageWindow(String name, JsonNode window, Instant reportedAt) {
        return new UsageWindow(
                name,
                readUtilization(window),
                Instant.ofEpochSecond(window.path("resetsAt").asLong()),
                window.path("status").asText(),
                reportedAt);
    }

    /** A missing or a text value is not a number, so the usage tracker treats the window as unreadable. */
    private static double readUtilization(JsonNode window) {
        JsonNode utilization = window.path("utilization");
        return utilization.isNumber() ? utilization.asDouble() : Double.NaN;
    }

    private SessionReport buildReport(JsonNode resultEvent) {
        boolean success = "success".equals(resultEvent.path("subtype").asText())
                && !resultEvent.path("is_error").asBoolean(true);
        return new SessionReport(
                success,
                resultEvent.path("result").asText(""),
                apiKeySource,
                findContextUsage(resultEvent),
                List.copyOf(usageWindows),
                false,
                findCost(resultEvent));
    }

    private static Optional<BigDecimal> findCost(JsonNode resultEvent) {
        JsonNode cost = resultEvent.path("total_cost_usd");
        return cost.isNumber() ? Optional.of(cost.decimalValue()) : Optional.empty();
    }

    private SessionReport buildMissingResultReport() {
        return new SessionReport(false, NO_RESULT_TEXT, apiKeySource, Optional.empty(), List.copyOf(usageWindows));
    }

    private Optional<ContextUsage> findContextUsage(JsonNode resultEvent) {
        Iterator<JsonNode> models = resultEvent.path("modelUsage").elements();
        if (tokensInUse.isEmpty() || !models.hasNext()) {
            return Optional.empty();
        }
        return Optional.of(new ContextUsage(tokensInUse.get(), models.next().path("contextWindow").asLong()));
    }
}
