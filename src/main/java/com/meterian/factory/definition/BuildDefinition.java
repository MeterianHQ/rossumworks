package com.meterian.factory.definition;

import java.util.Map;

public record BuildDefinition(String command, Map<String, String> environment) {
}
