package com.meterian.factory.definition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A part of the YAML tree of factory.yaml. It names the full key path in each error message. */
final class DefinitionNode {

    static final String MAIN_FILE = "factory.yaml";

    private final JsonNode node;
    private final String keyPath;

    DefinitionNode(JsonNode node, String keyPath) {
        this.node = node;
        this.keyPath = keyPath;
    }

    static DefinitionException createFailure(String message) {
        return new DefinitionException(MAIN_FILE + ": " + message);
    }

    boolean isObject() {
        return node.isObject();
    }

    boolean isText() {
        return node.isTextual();
    }

    String asText() {
        return node.asText();
    }

    boolean containsKey(String key) {
        JsonNode child = node.get(key);
        return child != null && !child.isNull();
    }

    /** The child under the key, whatever its kind. */
    DefinitionNode requireValue(String key) throws DefinitionException {
        requireKey(key);
        return child(key);
    }

    DefinitionNode requireObject(String key) throws DefinitionException {
        requireKey(key);
        return findObject(key).orElseThrow();
    }

    Optional<DefinitionNode> findObject(String key) throws DefinitionException {
        if (!containsKey(key)) {
            return Optional.empty();
        }
        if (!node.get(key).isObject()) {
            throw createInvalidKeyFailure(key, "must be a section");
        }
        return Optional.of(child(key));
    }

    String requireText(String key) throws DefinitionException {
        requireKey(key);
        return findText(key).orElseThrow();
    }

    Optional<String> findText(String key) throws DefinitionException {
        if (!containsKey(key)) {
            return Optional.empty();
        }
        JsonNode child = node.get(key);
        if (!child.isTextual() || child.asText().isBlank()) {
            throw createInvalidKeyFailure(key, "must be a text");
        }
        return Optional.of(child.asText());
    }

    Optional<Integer> findInteger(String key) throws DefinitionException {
        if (!containsKey(key)) {
            return Optional.empty();
        }
        if (!node.get(key).isInt()) {
            throw createInvalidKeyFailure(key, "must be an integer");
        }
        return Optional.of(node.get(key).asInt());
    }

    boolean findFlag(String key) throws DefinitionException {
        if (!containsKey(key)) {
            return false;
        }
        if (!node.get(key).isBoolean()) {
            throw createInvalidKeyFailure(key, "must be true or false");
        }
        return node.get(key).asBoolean();
    }

    List<String> findTextList(String key) throws DefinitionException {
        if (!containsKey(key)) {
            return List.of();
        }
        JsonNode list = node.get(key);
        if (!list.isArray()) {
            throw createInvalidKeyFailure(key, "must be a list");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : list) {
            if (!item.isTextual()) {
                throw createInvalidKeyFailure(key, "must be a list of texts");
            }
            values.add(item.asText());
        }
        return values;
    }

    /** The children of a section, in the order of the YAML. Each child is a text or a section. */
    Map<String, DefinitionNode> findChildren(String key) throws DefinitionException {
        Map<String, DefinitionNode> children = new LinkedHashMap<>();
        for (DefinitionNode section : findObject(key).stream().toList()) {
            section.node.fieldNames().forEachRemaining(name -> children.put(name, section.child(name)));
        }
        return children;
    }

    Map<String, String> findTextMap(String key) throws DefinitionException {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, DefinitionNode> entry : findChildren(key).entrySet()) {
            if (!entry.getValue().isText()) {
                throw createInvalidKeyFailure(key, "must have only texts as values");
            }
            values.put(entry.getKey(), entry.getValue().asText());
        }
        return values;
    }

    Map<String, Integer> findIntegerMap(String key) throws DefinitionException {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (Map.Entry<String, DefinitionNode> entry : findChildren(key).entrySet()) {
            if (!entry.getValue().node.isInt()) {
                throw createInvalidKeyFailure(key, "must have only integers as values");
            }
            values.put(entry.getKey(), entry.getValue().node.asInt());
        }
        return values;
    }

    void requireOnlyKeys(Set<String> allowedKeys) throws DefinitionException {
        Optional<String> unknownKey = findUnknownKey(allowedKeys);
        if (unknownKey.isPresent()) {
            throw createFailure("unknown key " + buildKeyPath(unknownKey.get()));
        }
    }

    /** The first key of the section, in the order of the YAML, that is not in the allowed keys. */
    Optional<String> findUnknownKey(Set<String> allowedKeys) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.stream().filter(name -> !allowedKeys.contains(name)).findFirst();
    }

    String keyPath() {
        return keyPath;
    }

    DefinitionException createInvalidKeyFailure(String key, String problem) {
        return createFailure("key " + buildKeyPath(key) + " " + problem);
    }

    private void requireKey(String key) throws DefinitionException {
        if (!containsKey(key)) {
            throw createFailure("key " + buildKeyPath(key) + " is missing");
        }
    }

    private DefinitionNode child(String key) {
        return new DefinitionNode(node.get(key), buildKeyPath(key));
    }

    private String buildKeyPath(String key) {
        return keyPath.isEmpty() ? key : keyPath + "." + key;
    }
}
