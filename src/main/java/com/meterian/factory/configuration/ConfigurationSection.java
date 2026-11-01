package com.meterian.factory.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A part of the YAML tree. It names the full key path in each error message. */
final class ConfigurationSection {

    private final JsonNode node;
    private final String keyPath;

    ConfigurationSection(JsonNode node, String keyPath) {
        this.node = node;
        this.keyPath = keyPath;
    }

    ConfigurationSection requireSection(String key) throws ConfigurationException {
        JsonNode child = requireChild(key);
        if (!child.isObject()) {
            throw createInvalidKeyException(key, "must be a section");
        }
        return new ConfigurationSection(child, buildKeyPath(key));
    }

    Optional<ConfigurationSection> findSection(String key) throws ConfigurationException {
        JsonNode child = node.get(key);
        if (child == null || child.isNull()) {
            return Optional.empty();
        }
        return Optional.of(requireSection(key));
    }

    String requireText(String key) throws ConfigurationException {
        JsonNode child = requireChild(key);
        if (!child.isTextual() || child.asText().isBlank()) {
            throw createInvalidKeyException(key, "must be a text");
        }
        return child.asText();
    }

    String requireNestedText(String sectionKey, String key) throws ConfigurationException {
        Optional<ConfigurationSection> section = findSection(sectionKey);
        if (section.isEmpty()) {
            throw new ConfigurationException("Configuration key " + buildKeyPath(sectionKey + "." + key)
                    + " is missing.");
        }
        return section.get().requireText(key);
    }

    Optional<String> findText(String key) throws ConfigurationException {
        return containsKey(key) ? Optional.of(requireText(key)) : Optional.empty();
    }

    int requireInteger(String key) throws ConfigurationException {
        JsonNode child = requireChild(key);
        if (!child.isInt()) {
            throw createInvalidKeyException(key, "must be an integer");
        }
        return child.asInt();
    }

    Optional<Integer> findInteger(String key) throws ConfigurationException {
        return containsKey(key) ? Optional.of(requireInteger(key)) : Optional.empty();
    }

    Optional<Double> findNumber(String key) throws ConfigurationException {
        if (!containsKey(key)) {
            return Optional.empty();
        }
        JsonNode child = node.get(key);
        if (!child.isNumber()) {
            throw createInvalidKeyException(key, "must be a number");
        }
        return Optional.of(child.asDouble());
    }

    Path requirePath(String key) throws ConfigurationException {
        return Path.of(requireText(key));
    }

    Optional<Path> findPath(String key) throws ConfigurationException {
        return findText(key).map(Path::of);
    }

    List<String> requireTextList(String key) throws ConfigurationException {
        JsonNode child = requireChild(key);
        if (!child.isArray()) {
            throw createInvalidKeyException(key, "must be a list");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : child) {
            if (!element.isTextual()) {
                throw createInvalidKeyException(key, "must be a list of texts");
            }
            values.add(element.asText());
        }
        return List.copyOf(values);
    }

    Optional<List<String>> findTextList(String key) throws ConfigurationException {
        return containsKey(key) ? Optional.of(requireTextList(key)) : Optional.empty();
    }

    Map<String, String> findTextMap(String key) throws ConfigurationException {
        if (!containsKey(key)) {
            return Map.of();
        }
        JsonNode child = node.get(key);
        if (!child.isObject()) {
            throw createInvalidKeyException(key, "must be a section of texts");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : child.properties()) {
            values.put(entry.getKey(), entry.getValue().asText());
        }
        return Map.copyOf(values);
    }

    ConfigurationException createInvalidKeyException(String key, String problem) {
        return new ConfigurationException("Configuration key " + buildKeyPath(key) + " " + problem + ".");
    }

    private boolean containsKey(String key) {
        JsonNode child = node.get(key);
        return child != null && !child.isNull();
    }

    private JsonNode requireChild(String key) throws ConfigurationException {
        if (!containsKey(key)) {
            throw new ConfigurationException("Configuration key " + buildKeyPath(key) + " is missing.");
        }
        return node.get(key);
    }

    private String buildKeyPath(String key) {
        return keyPath.isEmpty() ? key : keyPath + "." + key;
    }
}
