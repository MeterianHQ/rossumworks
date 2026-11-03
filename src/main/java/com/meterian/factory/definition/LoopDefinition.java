package com.meterian.factory.definition;

public record LoopDefinition(String name, String to, int limit, Transition exit) {
}
