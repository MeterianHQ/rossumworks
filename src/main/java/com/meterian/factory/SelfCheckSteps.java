package com.meterian.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.meterian.factory.agent.LiveFiles;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.events.EventsFile;
import com.meterian.factory.events.FactoryVersion;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.setup.DefaultFactoryFolder;
import com.meterian.factory.setup.GeneratedFactoryFolder;
import com.meterian.factory.setup.RepositoryBuild;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.state.Verdict;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/** The steps of the command self-check. Each step uses a part of the program that a native program can miss. */
final class SelfCheckSteps {

    static final String CHECK_REPOSITORY = "owner/project";
    private static final String CHECK_LOGIN = "self-check";
    private static final String UNKNOWN_VERSION = "unknown";

    private SelfCheckSteps() {
    }

    static List<SelfCheckStep> all() {
        return List.of(
                new SelfCheckStep("version", folder -> verifyVersion()),
                new SelfCheckStep("definition", folder -> verifyDefinition()),
                new SelfCheckStep("status comment", folder -> verifyStatusComment()),
                new SelfCheckStep("events", SelfCheckSteps::verifyEvents),
                new SelfCheckStep("live files", SelfCheckSteps::verifyLiveFiles),
                new SelfCheckStep("gatekeeper", SelfCheckSteps::verifyGatekeeper),
                new SelfCheckStep("board", BoardSelfCheck::verifyBoard));
    }

    private static void verifyVersion() {
        if (FactoryVersion.current().equals(UNKNOWN_VERSION)) {
            throw new IllegalStateException("The program has no version.");
        }
    }

    /** The built-in definition and the default .factory come from the resources, and Jackson reads their YAML. */
    private static void verifyDefinition() throws Exception {
        BuiltInDefinition.load();
        GeneratedFactoryFolder.verifyLoads(DefaultFactoryFolder.files(RepositoryBuild.MAVEN, "main"));
    }

    /** Jackson writes a record into the comment, and reads it again with reflection. */
    private static void verifyStatusComment() {
        IssueRecord record = IssueRecord.queued(1, "coding")
                .withReview("correctness", Verdict.APPROVE)
                .withRisk(RiskLevel.LOW, true, List.of("A small change."))
                .withNote("The self-check wrote this record.");
        String body = StatusComment.render(record, Map.of(), List.of("correctness"));
        IssueRecord readRecord = StatusComment.findRecord(List.of(new IssueComment(1, CHECK_LOGIN, body)), CHECK_LOGIN)
                .orElseThrow(() -> new IllegalStateException("The status comment has no record."));
        if (!readRecord.equals(record)) {
            throw new IllegalStateException("The record of the status comment changed: " + readRecord);
        }
    }

    private static void verifyEvents(Path folder) throws Exception {
        EventsFile eventsFile = EventsFile.inWorkFolder(folder);
        new WorkerEvents(eventsFile, Clock.systemUTC()).writeWorkerStart(FactoryVersion.current(), CHECK_REPOSITORY);
        List<String> lines = Files.exists(eventsFile.file()) ? Files.readAllLines(eventsFile.file()) : List.of();
        if (lines.size() != 1) {
            throw new IllegalStateException("The events file has " + lines.size() + " lines, not 1.");
        }
        JsonNode event = JsonMapper.builder().build().readTree(lines.getFirst());
        if (!event.isObject() || event.isEmpty()) {
            throw new IllegalStateException("The event is not a JSON object: " + lines.getFirst());
        }
    }

    private static void verifyLiveFiles(Path folder) {
        new LiveFiles(folder.resolve("live")).install();
    }

    /** The gatekeeper needs git, and its hooks come from the resources. */
    private static void verifyGatekeeper(Path folder) {
        Path gatekeeper = folder.resolve("gatekeeper.git");
        Gatekeeper.ensure(gatekeeper, "git@github.com:" + CHECK_REPOSITORY + ".git");
        if (!Files.isExecutable(gatekeeper.resolve("hooks/pre-receive"))) {
            throw new IllegalStateException("The gatekeeper has no executable pre-receive hook.");
        }
    }
}
