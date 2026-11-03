package com.meterian.factory.definition;

import com.meterian.factory.state.IssueStatus;
import java.util.Map;

public record StateDefinition(String name, IssueStatus status, StepDefinition step,
        Map<String, Transition> transitions) {
}
