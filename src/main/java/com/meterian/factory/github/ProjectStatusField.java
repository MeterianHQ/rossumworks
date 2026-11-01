package com.meterian.factory.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.meterian.factory.configuration.ProjectReference;
import com.meterian.factory.state.IssueStatus;
import java.util.HashMap;
import java.util.Map;

/** The Project id, the field "Status", and its option ids. It reads them once and keeps them. */
final class ProjectStatusField {

    /** Sends one GraphQL request and returns the "data" node. */
    interface GraphQlExecutor {
        JsonNode execute(String query, Map<String, Object> variables);
    }

    private static final String QUERY = "query($login:String!,$number:Int!){organization(login:$login)"
            + "{projectV2(number:$number){id field(name:\"Status\"){... on ProjectV2SingleSelectField"
            + "{id options{id name}}}}}}";

    private final ProjectReference project;
    private final GraphQlExecutor executor;
    private String projectId;
    private String fieldId;
    private final Map<String, String> optionIdsByName = new HashMap<>();

    ProjectStatusField(ProjectReference project, GraphQlExecutor executor) {
        this.project = project;
        this.executor = executor;
    }

    synchronized String projectId() {
        loadOnce();
        return projectId;
    }

    synchronized String fieldId() {
        loadOnce();
        return fieldId;
    }

    synchronized String optionId(IssueStatus status) {
        loadOnce();
        String optionId = optionIdsByName.get(status.displayName());
        if (optionId == null) {
            throw new GitHubException("the Project field Status has no option " + status.displayName());
        }
        return optionId;
    }

    private void loadOnce() {
        if (projectId != null) {
            return;
        }
        JsonNode projectNode = executor.execute(QUERY, Map.of("login", project.owner(), "number", project.number()))
                .path("organization").path("projectV2");
        JsonNode fieldNode = projectNode.path("field");
        if (projectNode.path("id").isMissingNode() || fieldNode.path("id").isMissingNode()) {
            throw new GitHubException("the Project " + project.owner() + "/" + project.number()
                    + " has no field Status");
        }
        for (JsonNode option : fieldNode.path("options")) {
            optionIdsByName.put(option.path("name").asText(), option.path("id").asText());
        }
        fieldId = fieldNode.path("id").asText();
        projectId = projectNode.path("id").asText();
    }
}
