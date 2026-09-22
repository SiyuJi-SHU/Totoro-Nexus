package org.example.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Immutable request input. A candidate may never replace it with another mock scene. */
public record IncidentSnapshot(String id, String scenarioId, String scenarioName, String symptoms,
                               String alertsJson, String logsJson, String capturedAt) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public JsonNode alerts() { return parse(alertsJson).path("alerts"); }
    public JsonNode logs() { return parse(logsJson).path("logs"); }
    private static JsonNode parse(String text) {
        try { return JSON.readTree(text); } catch (Exception e) { throw new IllegalArgumentException("现场数据格式无效", e); }
    }
    public boolean hasAlerts() { return alerts().isArray() && !alerts().isEmpty(); }
    public String service() { return hasAlerts() ? alerts().get(0).path("service").asText("") : ""; }
    public Map<String, String> observations() {
        Map<String, String> result = new LinkedHashMap<>(); int i = 0;
        for (JsonNode alert : alerts()) result.put("A" + (++i), observationText(alert));
        i = 0; for (JsonNode log : logs()) result.put("L" + (++i), observationText(log));
        return Collections.unmodifiableMap(result);
    }
    private static String observationText(JsonNode node) {
        StringBuilder out = new StringBuilder();
        node.fields().forEachRemaining(field -> {
            out.append(field.getKey()).append(": ").append(field.getValue().isValueNode()
                    ? field.getValue().asText() : field.getValue().toString()).append('\n');
        });
        return out.toString().strip();
    }
    public String query() {
        StringBuilder out = new StringBuilder(Objects.toString(symptoms, ""));
        for (JsonNode alert : alerts()) for (String field : List.of("alert_name", "service", "description"))
            out.append(' ').append(alert.path(field).asText(""));
        for (JsonNode log : logs()) out.append(' ').append(log.path("message").asText(""));
        String text = out.toString(); return text.substring(0, Math.min(text.length(), 5000));
    }
}
