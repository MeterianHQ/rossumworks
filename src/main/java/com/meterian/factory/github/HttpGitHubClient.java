package com.meterian.factory.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.configuration.ProjectReference;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.state.IssueStatus;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpGitHubClient implements GitHubClient {

    private static final String API_VERSION = "2022-11-28";
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final String ADD_ITEM = "mutation($project:ID!,$content:ID!){addProjectV2ItemById("
            + "input:{projectId:$project,contentId:$content}){item{id}}}";
    private static final String UPDATE_ITEM = "mutation($project:ID!,$item:ID!,$field:ID!,$option:String!)"
            + "{updateProjectV2ItemFieldValue(input:{projectId:$project,itemId:$item,fieldId:$field,"
            + "value:{singleSelectOptionId:$option}}){projectV2Item{id}}}";

    private final URI apiBase;
    private final String token;
    private final String repositoryPath;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectStatusField statusField;

    public HttpGitHubClient(URI apiBase, String token, TargetRepository target, ProjectReference project,
                            HttpClient httpClient) {
        this.apiBase = apiBase;
        this.token = token;
        this.repositoryPath = "/repos/" + target.fullName();
        this.httpClient = httpClient;
        this.statusField = new ProjectStatusField(project, this::executeGraphQl);
    }

    @Override
    public String authenticatedLogin() {
        return send("GET", "/user", null).path("login").asText();
    }

    @Override
    public List<Issue> openIssuesWithLabel(String label) {
        List<Issue> issues = new ArrayList<>();
        for (JsonNode item : readAllPages(repositoryPath + "/issues?labels=" + encode(label)
                + "&state=open&per_page=100")) {
            if (!item.has("pull_request")) {
                issues.add(toIssue(item));
            }
        }
        return issues;
    }

    @Override
    public Issue issue(int number) {
        return toIssue(send("GET", repositoryPath + "/issues/" + number, null));
    }

    @Override
    public List<IssueComment> comments(int issueNumber) {
        List<IssueComment> comments = new ArrayList<>();
        for (JsonNode item : readAllPages(repositoryPath + "/issues/" + issueNumber + "/comments?per_page=100")) {
            comments.add(new IssueComment(item.path("id").asLong(), item.path("user").path("login").asText(),
                    item.path("body").asText("")));
        }
        return comments;
    }

    @Override
    public long createComment(int issueNumber, String body) {
        return send("POST", repositoryPath + "/issues/" + issueNumber + "/comments", Map.of("body", body))
                .path("id").asLong();
    }

    @Override
    public void editComment(long commentId, String body) {
        send("PATCH", repositoryPath + "/issues/comments/" + commentId, Map.of("body", body));
    }

    @Override
    public Optional<String> lastLabelActor(int issueNumber, String label) {
        String newestTime = "";
        String newestActor = null;
        for (JsonNode event : readAllPages(repositoryPath + "/issues/" + issueNumber + "/events?per_page=100")) {
            boolean matches = "labeled".equals(event.path("event").asText())
                    && label.equals(event.path("label").path("name").asText());
            String createdAt = event.path("created_at").asText("");
            if (matches && createdAt.compareTo(newestTime) >= 0) {
                newestTime = createdAt;
                newestActor = event.path("actor").path("login").asText();
            }
        }
        return Optional.ofNullable(newestActor);
    }

    @Override
    public void ensureLabel(String label, String color) {
        HttpResponse<String> response = sendRaw("POST", repositoryPath + "/labels",
                Map.of("name", label, "color", color));
        boolean alreadyExists = response.statusCode() == 422;
        if (!alreadyExists) {
            requireSuccess("POST", repositoryPath + "/labels", response);
        }
    }

    @Override
    public void addLabel(int issueNumber, String label) {
        send("POST", repositoryPath + "/issues/" + issueNumber + "/labels", Map.of("labels", List.of(label)));
    }

    @Override
    public void removeLabel(int issueNumber, String label) {
        String path = repositoryPath + "/issues/" + issueNumber + "/labels/" + encode(label);
        HttpResponse<String> response = sendRaw("DELETE", path, null);
        if (response.statusCode() != 404) {
            requireSuccess("DELETE", path, response);
        }
    }

    @Override
    public void setStatus(Issue issue, IssueStatus status) {
        String optionId = statusField.optionId(status);
        JsonNode added = executeGraphQl(ADD_ITEM, Map.of("project", statusField.projectId(), "content", issue.nodeId()));
        String itemId = added.path("addProjectV2ItemById").path("item").path("id").asText();
        executeGraphQl(UPDATE_ITEM, Map.of("project", statusField.projectId(), "item", itemId,
                "field", statusField.fieldId(), "option", optionId));
    }

    @Override
    public Optional<PullRequest> openPullRequest(String headBranch) {
        String head = encode(repositoryOwner() + ":" + headBranch);
        return readAllPages(repositoryPath + "/pulls?head=" + head + "&state=open&per_page=100").stream()
                .findFirst().map(this::toPullRequest);
    }

    @Override
    public PullRequest createPullRequest(String headBranch, String baseBranch, String title, String body) {
        JsonNode created = send("POST", repositoryPath + "/pulls",
                Map.of("head", headBranch, "base", baseBranch, "title", title, "body", body));
        return toPullRequest(created);
    }

    @Override
    public MergeOutcome squashMerge(int pullRequestNumber) {
        String path = repositoryPath + "/pulls/" + pullRequestNumber + "/merge";
        HttpResponse<String> response = sendRaw("PUT", path, Map.of("merge_method", "squash"));
        if (response.statusCode() == 405) {
            return MergeOutcome.NOT_MERGEABLE;
        }
        requireSuccess("PUT", path, response);
        return MergeOutcome.MERGED;
    }

    private String repositoryOwner() {
        return repositoryPath.split("/")[2];
    }

    private JsonNode executeGraphQl(String query, Map<String, Object> variables) {
        JsonNode response = send("POST", "/graphql", Map.of("query", query, "variables", variables));
        if (response.has("errors")) {
            throw new GitHubException("GitHub GraphQL answered with errors: " + response.path("errors"));
        }
        return response.path("data");
    }

    private Issue toIssue(JsonNode item) {
        Set<String> labels = new LinkedHashSet<>();
        for (JsonNode label : item.path("labels")) {
            labels.add(label.path("name").asText());
        }
        return new Issue(item.path("number").asInt(), item.path("node_id").asText(), item.path("title").asText(),
                item.path("body").asText(""), labels);
    }

    private PullRequest toPullRequest(JsonNode item) {
        return new PullRequest(item.path("number").asInt(), item.path("html_url").asText(),
                item.path("head").path("ref").asText());
    }

    private List<JsonNode> readAllPages(String firstPath) {
        List<JsonNode> items = new ArrayList<>();
        URI next = apiBase.resolve(firstPath);
        while (next != null) {
            HttpResponse<String> response = sendRaw("GET", next, null);
            requireSuccess("GET", next.getRawPath(), response);
            parse(response.body()).forEach(items::add);
            next = nextPage(response);
        }
        return items;
    }

    private URI nextPage(HttpResponse<String> response) {
        Matcher matcher = NEXT_LINK.matcher(response.headers().firstValue("Link").orElse(""));
        if (!matcher.find()) {
            return null;
        }
        URI next = URI.create(matcher.group(1));
        boolean sameServer = next.getScheme().equals(apiBase.getScheme()) && next.getHost().equals(apiBase.getHost())
                && next.getPort() == apiBase.getPort();
        if (!sameServer) {
            throw new GitHubException("GitHub sent a next page link to another server");
        }
        return next;
    }

    private JsonNode send(String method, String path, Object payload) {
        HttpResponse<String> response = sendRaw(method, path, payload);
        requireSuccess(method, path, response);
        return parse(response.body());
    }

    private HttpResponse<String> sendRaw(String method, String path, Object payload) {
        return sendRaw(method, apiBase.resolve(path), payload);
    }

    private HttpResponse<String> sendRaw(String method, URI uri, Object payload) {
        try {
            HttpRequest.BodyPublisher publisher = payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .method(method, publisher)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .header("X-GitHub-Api-Version", API_VERSION)
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitHubException("the request " + method + " " + uri.getRawPath() + " was interrupted");
        } catch (IOException exception) {
            throw new GitHubException("the request " + method + " " + uri.getRawPath() + " failed: "
                    + exception.getClass().getSimpleName());
        }
    }

    private void requireSuccess(String method, String path, HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new GitHubException("GitHub answered " + response.statusCode() + " for " + method + " " + path);
        }
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new GitHubException("GitHub sent an answer that is not JSON");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
