# External Definition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The option `--definition <folder>` of `run` and `create` lets the factory use a definition folder outside the repository.

**Architecture:** A new interface `DefinitionSource` has two implementations. `GitDefinitionVersions` is the old `DefinitionVersions`, and it reads `.factory` from git. `FolderDefinitionVersions` reads the definition folder, and it keeps each version that loads as a definition copy in `<work folder>/definitions/<hash>/`. A version of a definition folder is `local:` and the SHA-256 hash of the content.

**Tech Stack:** Java 21, Maven (`./mvnw verify`), JUnit 6, AssertJ, Jackson.

**Design:** `docs/superpowers/specs/2026-11-26-external-definition-design.md`.

**Rules for each task:** Obey `CLAUDE.md`: full names, one responsibility for each class and method, comments of one line only, Simplified Technical English in comments, messages, and commit messages. End each commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Run each command from the worktree root `.worktree/external-definition`.

---

## File structure

| File | Responsibility |
|---|---|
| `definition/DefinitionSource.java` (new) | The interface of a source of definition versions. |
| `definition/GitDefinitionVersions.java` (renamed from `DefinitionVersions.java`) | The versions of `.factory` on the default branch. |
| `definition/DefinitionVersionText.java` (new) | The short text of a version, for the board, the notes, and the log. |
| `definition/LocalVersion.java` (new) | The text form `local:<hash>` of a version of a definition folder. |
| `definition/FolderHash.java` (new) | The SHA-256 hash of the content of a folder. |
| `definition/DefinitionCopies.java` (new) | The definition copies in `<work folder>/definitions`. |
| `definition/FolderDefinitionFiles.java` (new) | The `DefinitionFiles` of a folder on the disk. |
| `definition/FolderDefinitionVersions.java` (new) | The versions of a definition folder. |
| `RunOptions.java` (new, package `com.meterian.factory`) | The arguments of the command `run`. |
| `setup/DefinitionFolderSetup.java` (new) | The checks and the write of the definition folder for `create`. |
| `testing/DefinitionFolders.java` (new, test) | Writes a definition folder for a test. |

All the paths in this plan are below `src/main/java/com/meterian/factory/` or `src/test/java/com/meterian/factory/`, unless the path starts with `docs/`.

---

### Task 1: The record keeps a version, not only a commit

This task changes names only. The behavior does not change.

**Files:**
- Modify: `state/IssueRecord.java`
- Modify: `definition/DefinitionVersion.java`
- Modify: each file that uses `definitionCommit`, `withDefinitionCommit`, or `DefinitionVersion.commit()`
- Test: `state/StatusCommentTest.java`

- [ ] **Step 1: Write the failing test**

Add this test to `src/test/java/com/meterian/factory/state/StatusCommentTest.java`, after `writesAndReadsTheStateTheActionAndTheDefinitionCommit`:

```java
    @Test
    void keepsTheKeyDefinitionCommitForTheVersion() {
        IssueRecord withVersion = RecordStates.queued(7).withDefinitionVersion("local:" + "a".repeat(64));
        String body = StatusComment.render(withVersion, LIMIT, REVIEWS);
        IssueComment comment = new IssueComment(1, WORKER, body);

        assertThat(body).contains("\"definitionCommit\":\"local:" + "a".repeat(64) + "\"");
        assertThat(StatusComment.findRecord(List.of(comment), WORKER)).contains(withVersion);
    }
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw -q test -Dtest=StatusCommentTest`
Expected: a compilation failure: `cannot find symbol ... withDefinitionVersion`.

- [ ] **Step 3: Rename the field and the methods**

Run these commands. They rename the Java names in the main code and in the tests:

```bash
grep -rl "withDefinitionCommit" src | xargs sed -i 's/withDefinitionCommit/withDefinitionVersion/g'
grep -rl "definitionCommit" src | xargs sed -i 's/definitionCommit/definitionVersion/g'
```

In `state/IssueRecord.java`, add the import `import com.fasterxml.jackson.annotation.JsonProperty;`, and change the record component to:

```java
        @JsonProperty("definitionCommit") Optional<String> definitionVersion,
```

Change the method `withDefinitionVersion` to:

```java
    public IssueRecord withDefinitionVersion(String version) {
        return copy(fields -> fields.definitionVersion = Optional.of(version));
    }
```

Replace `definition/DefinitionVersion.java` with:

```java
package com.meterian.factory.definition;

/** A definition that loads, and its version: a commit of the default branch, or a local version. */
public record DefinitionVersion(String identifier, FactoryDefinition definition) {
}
```

Then replace each call of `.commit()` on a `DefinitionVersion` with `.identifier()`. The calls are in `worker/Worker.java` (`current.commit()`), `worker/StartAgainRequests.java` (`current.commit()`), `definition/DefinitionVersions.java` (`version.commit()`), and in `src/test/java/com/meterian/factory/definition/DefinitionVersionsTest.java`. Find them with:

```bash
grep -rn "current()\.\(map\|orElseThrow\)\|current\.commit()\|version\.commit()\|\.commit())" src | grep -v "pushed.commit"
```

- [ ] **Step 4: Run all the tests**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS. The old test `writesAndReadsTheStateTheActionAndTheDefinitionCommit` still passes, so the old status comments still load.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Rename the definition commit of the record to the definition version

The key in the status comment stays definitionCommit, so the old
records and the older releases still read it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The interface DefinitionSource

This task changes names and types only. The behavior does not change.

**Files:**
- Create: `definition/DefinitionSource.java`
- Rename: `definition/DefinitionVersions.java` to `definition/GitDefinitionVersions.java`
- Rename: `src/test/.../definition/DefinitionVersionsTest.java` to `GitDefinitionVersionsTest.java`
- Create: `definition/DefinitionVersionText.java`
- Modify: `Main.java`, `worker/Worker.java`, `worker/StartAgainRequests.java`, `workflow/IssueWorkflow.java`, `worker/WorkerTest.java`, `worker/GitHubRequestBudgetTest.java`

- [ ] **Step 1: Create the interface**

Create `src/main/java/com/meterian/factory/definition/DefinitionSource.java`:

```java
package com.meterian.factory.definition;

import java.util.Optional;

/** A source of definition versions. The newest version that loads is the current version. */
public interface DefinitionSource extends DefinitionLookup {

    /** Reads the source again. A new version that loads becomes the current version. */
    void refresh();

    /** The version that a new issue gets. Empty when no version loads. */
    Optional<DefinitionVersion> current();

    /** The reason why the newest version does not load, for the top of the board. */
    Optional<String> warning();
}
```

- [ ] **Step 2: Create the short text of a version**

Create `src/main/java/com/meterian/factory/definition/DefinitionVersionText.java`:

```java
package com.meterian.factory.definition;

/** The short text of a version, as the board, the notes, and the log show it. */
public final class DefinitionVersionText {

    private static final int SHORT_LENGTH = 7;
    private static final String LOCAL_PREFIX = "local:";

    private DefinitionVersionText() {
    }

    /** The first 7 characters of a commit, or local: and the first 7 characters of the hash. */
    public static String shorten(String version) {
        if (version.startsWith(LOCAL_PREFIX)) {
            return LOCAL_PREFIX + shortenText(version.substring(LOCAL_PREFIX.length()));
        }
        return shortenText(version);
    }

    private static String shortenText(String text) {
        return text.length() <= SHORT_LENGTH ? text : text.substring(0, SHORT_LENGTH);
    }
}
```

Create `src/test/java/com/meterian/factory/definition/DefinitionVersionTextTest.java`:

```java
package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefinitionVersionTextTest {

    @Test
    void shortensACommit() {
        assertThat(DefinitionVersionText.shorten("3f2a9c1d8e")).isEqualTo("3f2a9c1");
    }

    @Test
    void keepsThePrefixOfALocalVersion() {
        assertThat(DefinitionVersionText.shorten("local:8be41d0aa77")).isEqualTo("local:8be41d0");
    }

    @Test
    void keepsAShortText() {
        assertThat(DefinitionVersionText.shorten("built-in")).isEqualTo("built-in");
    }
}
```

- [ ] **Step 3: Rename the class**

```bash
git mv src/main/java/com/meterian/factory/definition/DefinitionVersions.java src/main/java/com/meterian/factory/definition/GitDefinitionVersions.java
git mv src/test/java/com/meterian/factory/definition/DefinitionVersionsTest.java src/test/java/com/meterian/factory/definition/GitDefinitionVersionsTest.java
grep -rl "DefinitionVersions\b" src | xargs sed -i 's/\bDefinitionVersions\b/GitDefinitionVersions/g'
sed -i 's/class DefinitionVersionsTest/class GitDefinitionVersionsTest/' src/test/java/com/meterian/factory/definition/GitDefinitionVersionsTest.java
```

In `GitDefinitionVersions.java`:

- Change the class line to `public final class GitDefinitionVersions implements DefinitionSource {`.
- Add `@Override` to `refresh()`, `current()`, and `warning()`.
- Delete the constant `SHORT_COMMIT_LENGTH` and the method `shorten`. Replace each call `shorten(` with `DefinitionVersionText.shorten(`.

In `workflow/IssueWorkflow.java`, replace `GitDefinitionVersions.shorten(` with `DefinitionVersionText.shorten(`, and fix the imports.

- [ ] **Step 4: Use the interface**

Change the type `GitDefinitionVersions` to `DefinitionSource` in these places:

- `worker/Worker.java`: the field `definitions` and the constructor parameter.
- `worker/StartAgainRequests.java`: the field `definitions` and the constructor parameter.
- `Main.java`: the local variable in `runWorker`, and the parameter of `createWorkflow`. The method `createDefinitionVersions` keeps the return type `GitDefinitionVersions`.
- `worker/WorkerTest.java`: the parameter `versions` of `createWorker(FakeAgentRunner, FactoryConfiguration, BoardUpdates, ...)`.

Fix the imports in each file.

- [ ] **Step 5: Run all the tests**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "Add the interface DefinitionSource for the versions of the definition

DefinitionVersions becomes GitDefinitionVersions. The worker uses only
the interface, so a second source can come next.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The local version, and the git mode refuses it

**Files:**
- Create: `definition/LocalVersion.java`
- Test: `src/test/.../definition/LocalVersionTest.java`
- Modify: `definition/GitDefinitionVersions.java`
- Test: `src/test/.../definition/GitDefinitionVersionsTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/meterian/factory/definition/LocalVersionTest.java`:

```java
package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalVersionTest {

    private static final String HASH = "0123456789abcdef".repeat(4);

    @Test
    void addsThePrefixToTheHash() {
        assertThat(LocalVersion.of(HASH)).isEqualTo("local:" + HASH);
    }

    @Test
    void findsTheHashOfALocalVersion() {
        assertThat(LocalVersion.findHash("local:" + HASH)).contains(HASH);
    }

    @Test
    void findsNoHashInACommit() {
        assertThat(LocalVersion.findHash(HASH)).isEmpty();
    }

    @Test
    void findsNoHashWhenTheHashIsNotValid() {
        assertThat(LocalVersion.findHash("local:../../etc")).isEmpty();
        assertThat(LocalVersion.findHash("local:" + HASH.toUpperCase())).isEmpty();
        assertThat(LocalVersion.findHash("local:" + HASH.substring(1))).isEmpty();
    }

    @Test
    void knowsALocalVersion() {
        assertThat(LocalVersion.isLocal("local:anything")).isTrue();
        assertThat(LocalVersion.isLocal(HASH)).isFalse();
    }
}
```

Add this test to `GitDefinitionVersionsTest.java`:

```java
    @Test
    void refusesALocalVersionInTheRecord() {
        commitDefinition(author, builtInMainFile());
        GitDefinitionVersions versions = createVersions();
        versions.refresh();
        IssueRecord record = RecordStates.queued(7).withDefinitionVersion("local:" + "a".repeat(64));

        assertThatThrownBy(() -> versions.definitionOf(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The version local:aaaaaaa of the record comes from a definition folder, "
                        + "but this worker reads .factory of the branch main");
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest='LocalVersionTest,GitDefinitionVersionsTest'`
Expected: a compilation failure: `cannot find symbol ... LocalVersion`.

- [ ] **Step 3: Write the code**

Create `src/main/java/com/meterian/factory/definition/LocalVersion.java`:

```java
package com.meterian.factory.definition;

import java.util.Optional;
import java.util.regex.Pattern;

/** The version of a definition folder: local: and the SHA-256 hash of the content, in lowercase hexadecimal. */
public final class LocalVersion {

    private static final String PREFIX = "local:";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private LocalVersion() {
    }

    public static String of(String hash) {
        return PREFIX + hash;
    }

    public static boolean isLocal(String version) {
        return version.startsWith(PREFIX);
    }

    /** The hash only when it is valid, because the hash becomes the name of a folder. */
    public static Optional<String> findHash(String version) {
        if (!isLocal(version)) {
            return Optional.empty();
        }
        String hash = version.substring(PREFIX.length());
        return HASH.matcher(hash).matches() ? Optional.of(hash) : Optional.empty();
    }
}
```

In `GitDefinitionVersions.definitionOfRecordCommit`, add this check as the first statement:

```java
        if (LocalVersion.isLocal(commit)) {
            throw new IllegalStateException("The version " + DefinitionVersionText.shorten(commit) + " of the "
                    + "record comes from a definition folder, but this worker reads .factory of the branch "
                    + defaultBranch);
        }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='LocalVersionTest,GitDefinitionVersionsTest'`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Add the local version, and refuse it in the git mode

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The hash of a folder

**Files:**
- Create: `definition/FolderHash.java`
- Test: `src/test/.../definition/FolderHashTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/meterian/factory/definition/FolderHashTest.java`:

```java
package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderHashTest {

    @TempDir
    Path temporaryDirectory;

    private Path writeFolder(String name, String mainFile, String prompt) throws IOException {
        Path folder = Files.createDirectories(temporaryDirectory.resolve(name).resolve("prompts"))
                .getParent();
        Files.writeString(folder.resolve("factory.yaml"), mainFile);
        Files.writeString(folder.resolve("prompts/implement.md"), prompt);
        return folder;
    }

    @Test
    void givesTheSameHashForTheSameContent() throws IOException {
        Path first = writeFolder("first", "version: 1\n", "Implement.\n");
        Path second = writeFolder("second", "version: 1\n", "Implement.\n");

        assertThat(FolderHash.calculate(first)).isEqualTo(FolderHash.calculate(second)).hasSize(64);
    }

    @Test
    void givesANewHashForANewContent() throws IOException {
        Path first = writeFolder("first", "version: 1\n", "Implement.\n");
        Path second = writeFolder("second", "version: 1\n", "Implement it.\n");

        assertThat(FolderHash.calculate(first)).isNotEqualTo(FolderHash.calculate(second));
    }

    @Test
    void givesANewHashForANewPath() throws IOException {
        Path first = writeFolder("first", "version: 1\n", "Implement.\n");
        Path second = writeFolder("second", "version: 1\n", "Implement.\n");
        Files.move(second.resolve("prompts/implement.md"), second.resolve("prompts/fix.md"));

        assertThat(FolderHash.calculate(first)).isNotEqualTo(FolderHash.calculate(second));
    }

    @Test
    void givesANewHashForANewEmptyFile() throws IOException {
        Path folder = writeFolder("first", "version: 1\n", "Implement.\n");
        String before = FolderHash.calculate(folder);
        Files.writeString(folder.resolve("empty.md"), "");

        assertThat(FolderHash.calculate(folder)).isNotEqualTo(before);
    }

    @Test
    void givesANewHashWhenTextMovesFromOneFileToTheNext() throws IOException {
        Path first = writeFolder("first", "version: 1\n", "Implement.\n");
        Path second = writeFolder("second", "version: 1\nImp", "lement.\n");

        assertThat(FolderHash.calculate(first)).isNotEqualTo(FolderHash.calculate(second));
    }

    @Test
    void refusesASymbolicLink() throws IOException {
        Path folder = writeFolder("first", "version: 1\n", "Implement.\n");
        Files.createSymbolicLink(folder.resolve("link.md"), folder.resolve("factory.yaml"));

        assertThatThrownBy(() -> FolderHash.calculate(folder))
                .isInstanceOf(IOException.class)
                .hasMessage("The definition folder " + folder + " has the entry link.md, "
                        + "which is not a file or a folder.");
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=FolderHashTest`
Expected: a compilation failure: `cannot find symbol ... FolderHash`.

- [ ] **Step 3: Write the code**

Create `src/main/java/com/meterian/factory/definition/FolderHash.java`:

```java
package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** The SHA-256 hash of the files of a folder: the same content always gives the same hash. */
public final class FolderHash {

    private static final String ALGORITHM = "SHA-256";

    private FolderHash() {
    }

    /**
     * @return the hash in lowercase hexadecimal, 64 characters
     * @throws IOException when a file cannot be read, or when an entry is not a file or a folder
     */
    public static String calculate(Path folder) throws IOException {
        MessageDigest digest = createDigest();
        for (Path file : listFiles(folder)) {
            addFile(digest, folder.relativize(file), Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The regular files of the folder and of its subfolders, sorted by their relative path. */
    public static List<Path> listFiles(Path folder) throws IOException {
        try (Stream<Path> entries = Files.walk(folder)) {
            List<Path> sorted = entries.sorted().toList();
            for (Path entry : sorted) {
                verifyIsFileOrFolder(folder, entry);
            }
            return sorted.stream().filter(entry -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)).toList();
        }
    }

    private static void verifyIsFileOrFolder(Path folder, Path entry) throws IOException {
        boolean isFile = Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS);
        boolean isFolder = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
        if (!isFile && !isFolder) {
            throw new IOException("The definition folder " + folder + " has the entry " + folder.relativize(entry)
                    + ", which is not a file or a folder.");
        }
    }

    /** The lengths separate the path and the content, so text that moves from one file to the next changes the hash. */
    private static void addFile(MessageDigest digest, Path relativePath, byte[] content) {
        byte[] path = relativePath.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(path.length).array());
        digest.update(path);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
        digest.update(content);
    }

    private static MessageDigest createDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The JDK has no " + ALGORITHM + ".", exception);
        }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest=FolderHashTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Add the hash of the content of a definition folder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The definition copies

**Files:**
- Create: `definition/DefinitionCopies.java`
- Test: `src/test/.../definition/DefinitionCopiesTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/meterian/factory/definition/DefinitionCopiesTest.java`:

```java
package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionCopiesTest {

    @TempDir
    Path temporaryDirectory;

    private Path source;
    private DefinitionCopies copies;

    @BeforeEach
    void writeTheSource() throws IOException {
        source = Files.createDirectories(temporaryDirectory.resolve("definition/prompts")).getParent();
        Files.writeString(source.resolve("factory.yaml"), "version: 1\n");
        Files.writeString(source.resolve("prompts/implement.md"), "Implement.\n");
        copies = DefinitionCopies.inWorkFolder(temporaryDirectory.resolve("work"));
    }

    @Test
    void keepsACompleteCopyUnderItsHash() throws IOException {
        Path temporaryCopy = copies.copyToTemporaryFolder(source);
        String hash = FolderHash.calculate(temporaryCopy);

        copies.keep(temporaryCopy, hash);

        assertThat(copies.exists(hash)).isTrue();
        assertThat(FolderHash.calculate(copies.folderOf(hash))).isEqualTo(hash);
        assertThat(temporaryCopy).doesNotExist();
    }

    @Test
    void keepsTheFirstCopyOfAVersion() throws IOException {
        Path firstCopy = copies.copyToTemporaryFolder(source);
        String hash = FolderHash.calculate(firstCopy);
        copies.keep(firstCopy, hash);
        Path secondCopy = copies.copyToTemporaryFolder(source);

        copies.keep(secondCopy, hash);

        assertThat(copies.exists(hash)).isTrue();
        assertThat(secondCopy).doesNotExist();
    }

    @Test
    void listsTheCopiesNewestFirstAndSkipsTheTemporaryFolders() throws IOException {
        Path olderCopy = copies.copyToTemporaryFolder(source);
        String olderHash = FolderHash.calculate(olderCopy);
        copies.keep(olderCopy, olderHash);
        Files.writeString(source.resolve("factory.yaml"), "version: 1\n# new\n");
        Path newerCopy = copies.copyToTemporaryFolder(source);
        String newerHash = FolderHash.calculate(newerCopy);
        copies.keep(newerCopy, newerHash);
        Files.setLastModifiedTime(copies.folderOf(olderHash), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        copies.copyToTemporaryFolder(source);

        assertThat(copies.listNewestFirst()).containsExactly(newerHash, olderHash);
    }

    @Test
    void listsNothingWithoutAFolder() throws IOException {
        assertThat(copies.listNewestFirst()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=DefinitionCopiesTest`
Expected: a compilation failure: `cannot find symbol ... DefinitionCopies`.

- [ ] **Step 3: Write the code**

Create `src/main/java/com/meterian/factory/definition/DefinitionCopies.java`:

```java
package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The definition copies in <work folder>/definitions: one folder for each version, with the hash as its name. */
public final class DefinitionCopies {

    private static final String FOLDER = "definitions";
    private static final String TEMPORARY_PREFIX = ".partial-";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final Path directory;

    public DefinitionCopies(Path directory) {
        this.directory = directory;
    }

    public static DefinitionCopies inWorkFolder(Path workDirectory) {
        return new DefinitionCopies(workDirectory.resolve(FOLDER));
    }

    public Path directory() {
        return directory;
    }

    public Path folderOf(String hash) {
        return directory.resolve(hash);
    }

    public boolean exists(String hash) {
        return Files.isDirectory(folderOf(hash));
    }

    /** Copies the files of the source to a new temporary folder in the directory of the copies. */
    public Path copyToTemporaryFolder(Path source) throws IOException {
        Files.createDirectories(directory);
        Path temporaryFolder = Files.createTempDirectory(directory, TEMPORARY_PREFIX);
        try {
            for (Path file : FolderHash.listFiles(source)) {
                Path target = temporaryFolder.resolve(source.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        } catch (IOException failure) {
            discard(temporaryFolder);
            throw failure;
        }
        return temporaryFolder;
    }

    /** The rename makes the copy complete in one step. A copy that exists stays, and the temporary folder goes. */
    public void keep(Path temporaryFolder, String hash) throws IOException {
        if (exists(hash)) {
            discard(temporaryFolder);
            return;
        }
        try {
            Files.move(temporaryFolder, folderOf(hash), StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException exception) {
            discard(temporaryFolder);
        }
    }

    public void discard(Path temporaryFolder) throws IOException {
        if (!Files.exists(temporaryFolder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(temporaryFolder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** The hashes of the copies, the newest modification time first. Temporary folders do not count. */
    public List<String> listNewestFirst() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> folders = Files.list(directory)) {
            return folders.filter(folder -> HASH.matcher(folder.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(DefinitionCopies::readModificationTime).reversed())
                    .map(folder -> folder.getFileName().toString())
                    .toList();
        }
    }

    private static FileTime readModificationTime(Path folder) {
        try {
            return Files.getLastModifiedTime(folder);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest=DefinitionCopiesTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Add the definition copies in the work folder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The files of a folder on the disk

**Files:**
- Create: `definition/FolderDefinitionFiles.java`
- Test: `src/test/.../definition/FolderDefinitionFilesTest.java`
- Create: `src/test/.../testing/DefinitionFolders.java`

- [ ] **Step 1: Write the test helper**

Create `src/test/java/com/meterian/factory/testing/DefinitionFolders.java`:

```java
package com.meterian.factory.testing;

import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.ClasspathDefinitionFiles;
import com.meterian.factory.definition.DefinitionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a definition folder for a test: a main file and the prompts of the built-in definition. */
public final class DefinitionFolders {

    private static final String MAIN_FILE = "factory.yaml";

    private DefinitionFolders() {
    }

    public static Path writeDefinitionFolder(Path folder, String mainFile) {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(MAIN_FILE), mainFile);
            for (String promptPath : BuiltInDefinition.load().prompts().keySet()) {
                Path target = folder.resolve(promptPath);
                Files.createDirectories(target.getParent());
                Files.writeString(target, new ClasspathDefinitionFiles().read(promptPath).orElseThrow());
            }
            return folder;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (DefinitionException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/com/meterian/factory/definition/FolderDefinitionFilesTest.java`:

```java
package com.meterian.factory.definition;

import static com.meterian.factory.testing.DefinitionCommits.builtInMainFile;
import static com.meterian.factory.testing.DefinitionFolders.writeDefinitionFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderDefinitionFilesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheDefinitionOfTheFolder() throws DefinitionException {
        Path folder = writeDefinitionFolder(temporaryDirectory.resolve("definition"), builtInMainFile());

        FactoryDefinition definition = DefinitionLoader.load(new FolderDefinitionFiles(folder));

        assertThat(definition.workflow().start()).isEqualTo(BuiltInDefinition.load().workflow().start());
    }

    @Test
    void findsNoMissingFile() throws DefinitionException {
        Path folder = temporaryDirectory.resolve("empty");

        assertThat(new FolderDefinitionFiles(folder).read("factory.yaml")).isEmpty();
    }

    @Test
    void refusesAPathThatLeavesTheFolder() {
        FolderDefinitionFiles files = new FolderDefinitionFiles(temporaryDirectory);

        assertThatThrownBy(() -> files.read("../secret.md")).isInstanceOf(DefinitionException.class);
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=FolderDefinitionFilesTest`
Expected: a compilation failure: `cannot find symbol ... FolderDefinitionFiles`.

- [ ] **Step 4: Write the code**

Create `src/main/java/com/meterian/factory/definition/FolderDefinitionFiles.java`:

```java
package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** The definition files of a folder on the disk: a definition folder or a definition copy. */
public final class FolderDefinitionFiles implements DefinitionFiles {

    private final Path folder;

    public FolderDefinitionFiles(Path folder) {
        this.folder = folder;
    }

    @Override
    public Optional<String> read(String relativePath) throws DefinitionException {
        DefinitionFiles.verifyPathStaysInFolder(relativePath);
        Path file = folder.resolve(relativePath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new DefinitionException("Cannot read the file " + relativePath + " of the folder " + folder
                    + ": " + exception.getMessage(), exception);
        }
    }
}
```

If `DefinitionException` has no constructor `(String, Throwable)`, look at `GitDefinitionFiles`: it uses the same constructor, so it exists.

- [ ] **Step 5: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest=FolderDefinitionFilesTest`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "Read the definition files from a folder on the disk

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The versions of a definition folder

**Files:**
- Create: `definition/FolderDefinitionVersions.java`
- Test: `src/test/.../definition/FolderDefinitionVersionsTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/meterian/factory/definition/FolderDefinitionVersionsTest.java`:

```java
package com.meterian.factory.definition;

import static com.meterian.factory.testing.DefinitionCommits.builtInMainFile;
import static com.meterian.factory.testing.DefinitionFolders.writeDefinitionFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.RecordStates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderDefinitionVersionsTest {

    private static final String BROKEN_MAIN_FILE = "version: 1\nworkflow: [\n";
    /** A static reference: the logging framework keeps only a weak reference to a logger. */
    private static final Logger VERSIONS_LOGGER = Logger.getLogger(FolderDefinitionVersions.class.getName());

    @TempDir
    Path temporaryDirectory;

    private final FactoryDefinition builtIn = BuiltInDefinition.load();
    private Path folder;
    private DefinitionCopies copies;

    /** The tests cause the warnings on purpose, so the log is only noise. */
    @BeforeAll
    static void silenceTheLog() {
        VERSIONS_LOGGER.setLevel(Level.OFF);
    }

    @BeforeEach
    void createTheFolders() {
        folder = temporaryDirectory.resolve("definition");
        copies = DefinitionCopies.inWorkFolder(temporaryDirectory.resolve("work"));
    }

    private FolderDefinitionVersions createVersions() {
        return new FolderDefinitionVersions(folder, copies, builtIn);
    }

    private static String mainFileWithImplementLimit(int entries) {
        return builtInMainFile().replace("limit: { entries: 3, then: blocked }",
                "limit: { entries: " + entries + ", then: blocked }");
    }

    private static int implementLimitOf(FactoryDefinition definition) {
        return definition.workflow().state("implement").limit().orElseThrow().entries();
    }

    private String currentVersion(FolderDefinitionVersions versions) {
        return versions.current().orElseThrow().identifier();
    }

    @Test
    void loadsTheFolderAndKeepsACopy() throws IOException {
        writeDefinitionFolder(folder, builtInMainFile());
        FolderDefinitionVersions versions = createVersions();

        versions.refresh();

        String hash = FolderHash.calculate(folder);
        assertThat(currentVersion(versions)).isEqualTo(LocalVersion.of(hash));
        assertThat(copies.exists(hash)).isTrue();
        assertThat(versions.warning()).isEmpty();
    }

    @Test
    void takesANewVersionAfterAnEdit() {
        writeDefinitionFolder(folder, mainFileWithImplementLimit(3));
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        String first = currentVersion(versions);
        writeDefinitionFolder(folder, mainFileWithImplementLimit(5));

        versions.refresh();

        assertThat(currentVersion(versions)).isNotEqualTo(first);
        assertThat(implementLimitOf(versions.current().orElseThrow().definition())).isEqualTo(5);
    }

    @Test
    void keepsTheCurrentVersionWhenTheEditDoesNotLoad() {
        writeDefinitionFolder(folder, builtInMainFile());
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        String good = currentVersion(versions);
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);

        versions.refresh();

        assertThat(currentVersion(versions)).isEqualTo(good);
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .startsWith("The definition folder " + folder + " does not load: ")
                .endsWith("The factory keeps the version " + DefinitionVersionText.shorten(good) + "."));
        assertThat(copies.listNewestFirst()).hasSize(1);
    }

    @Test
    void removesTheWarningAfterACorrection() {
        writeDefinitionFolder(folder, builtInMainFile());
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);
        versions.refresh();
        writeDefinitionFolder(folder, mainFileWithImplementLimit(5));

        versions.refresh();

        assertThat(versions.warning()).isEmpty();
        assertThat(implementLimitOf(versions.current().orElseThrow().definition())).isEqualTo(5);
    }

    @Test
    void hasNoVersionWhenNothingLoads() {
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);
        FolderDefinitionVersions versions = createVersions();

        versions.refresh();

        assertThat(versions.current()).isEmpty();
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .endsWith("The factory takes no new issue."));
    }

    @Test
    void keepsTheCurrentVersionWhenTheFolderCannotBeRead() throws IOException {
        writeDefinitionFolder(folder, builtInMainFile());
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        String good = currentVersion(versions);
        Files.createSymbolicLink(folder.resolve("link.md"), folder.resolve("factory.yaml"));

        versions.refresh();

        assertThat(currentVersion(versions)).isEqualTo(good);
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .contains("link.md, which is not a file or a folder."));
    }

    @Test
    void takesTheNewestCopyThatLoadsAtTheStartWhenTheFolderDoesNotLoad() {
        writeDefinitionFolder(folder, mainFileWithImplementLimit(5));
        FolderDefinitionVersions earlierRun = createVersions();
        earlierRun.refresh();
        String good = currentVersion(earlierRun);
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);
        FolderDefinitionVersions versions = createVersions();

        versions.refresh();

        assertThat(currentVersion(versions)).isEqualTo(good);
        assertThat(versions.warning()).isPresent();
    }

    @Test
    void givesTheRecordTheDefinitionOfItsCopy() {
        writeDefinitionFolder(folder, mainFileWithImplementLimit(5));
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        IssueRecord record = RecordStates.queued(7).withDefinitionVersion(currentVersion(versions));
        writeDefinitionFolder(folder, mainFileWithImplementLimit(2));
        versions.refresh();

        assertThat(implementLimitOf(versions.definitionOf(record))).isEqualTo(5);
        assertThat(implementLimitOf(createVersions().definitionOf(record))).isEqualTo(5);
    }

    @Test
    void givesARecordWithoutAVersionTheBuiltInDefinition() {
        assertThat(createVersions().definitionOf(RecordStates.queued(7))).isSameAs(builtIn);
    }

    @Test
    void refusesALocalVersionWithoutACopy() {
        IssueRecord record = RecordStates.queued(7).withDefinitionVersion(LocalVersion.of("b".repeat(64)));

        assertThatThrownBy(() -> createVersions().definitionOf(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The version local:bbbbbbb of the record has no definition copy in "
                        + copies.directory());
    }

    @Test
    void refusesALocalVersionWithAHashThatIsNotValid() {
        IssueRecord record = RecordStates.queued(7).withDefinitionVersion("local:../../etc");

        assertThatThrownBy(() -> createVersions().definitionOf(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The version local:../../e of the record is not a valid local version");
    }

    @Test
    void refusesACommitInTheRecord() {
        IssueRecord record = RecordStates.queued(7).withDefinitionVersion("3f2a9c1d8e");

        assertThatThrownBy(() -> createVersions().definitionOf(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The version 3f2a9c1 of the record is a commit of .factory, but this worker reads "
                        + "the definition folder " + folder);
    }

    @Test
    void showsTheColumnsOfTheBuiltInDefinitionWithoutAVersion() {
        assertThat(createVersions().findCurrentColumns()).isEqualTo(builtIn.workflow().columns());
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=FolderDefinitionVersionsTest`
Expected: a compilation failure: `cannot find symbol ... FolderDefinitionVersions`.

- [ ] **Step 3: Write the code**

Create `src/main/java/com/meterian/factory/definition/FolderDefinitionVersions.java`:

```java
package com.meterian.factory.definition;

import com.meterian.factory.state.IssueRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The versions of a definition folder. Each version that loads gets a definition copy, and an issue reads its
 * definition only from its copy. So an edit of the folder reaches only the new issues.
 */
public final class FolderDefinitionVersions implements DefinitionSource {

    private static final System.Logger LOGGER = System.getLogger(FolderDefinitionVersions.class.getName());

    private final Path folder;
    private final DefinitionCopies copies;
    private final FactoryDefinition builtIn;
    private final Map<String, FactoryDefinition> definitionsByVersion = new ConcurrentHashMap<>();
    private volatile Optional<DefinitionVersion> current = Optional.empty();
    private volatile Optional<String> warning = Optional.empty();
    /** The hash of the folder at the last refresh. The same hash needs no new read. */
    private String lastFolderHash;

    public FolderDefinitionVersions(Path folder, DefinitionCopies copies, FactoryDefinition builtIn) {
        this.folder = folder;
        this.copies = copies;
        this.builtIn = builtIn;
    }

    /** A failure of the read keeps the current version, and the next refresh reads the folder again. */
    @Override
    public synchronized void refresh() {
        try {
            String folderHash = FolderHash.calculate(folder);
            if (folderHash.equals(lastFolderHash)) {
                return;
            }
            loadNewContent();
            lastFolderHash = folderHash;
        } catch (IOException exception) {
            warning = Optional.of("The definition folder " + folder + " cannot be read: " + exception.getMessage());
            LOGGER.log(System.Logger.Level.WARNING, warning.get());
        }
    }

    /** The hash and the load use the same temporary copy, so the version always matches its content. */
    private void loadNewContent() throws IOException {
        Path temporaryCopy = copies.copyToTemporaryFolder(folder);
        String hash = FolderHash.calculate(temporaryCopy);
        try {
            FactoryDefinition definition = DefinitionLoader.load(new FolderDefinitionFiles(temporaryCopy));
            copies.keep(temporaryCopy, hash);
            String version = LocalVersion.of(hash);
            definitionsByVersion.put(version, definition);
            current = Optional.of(new DefinitionVersion(version, definition));
            warning = Optional.empty();
        } catch (DefinitionException exception) {
            copies.discard(temporaryCopy);
            if (current.isEmpty()) {
                current = findNewestCopyThatLoads();
            }
            warning = Optional.of(describeFailure(exception.getMessage()));
            LOGGER.log(System.Logger.Level.WARNING, warning.get());
        }
    }

    private Optional<DefinitionVersion> findNewestCopyThatLoads() throws IOException {
        for (String hash : copies.listNewestFirst()) {
            String version = LocalVersion.of(hash);
            try {
                return Optional.of(new DefinitionVersion(version, definitionOfVersion(version)));
            } catch (IllegalStateException exception) {
                LOGGER.log(System.Logger.Level.INFO, "The definition copy {0} does not load: {1}",
                        DefinitionVersionText.shorten(version), exception.getMessage());
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<DefinitionVersion> current() {
        return current;
    }

    @Override
    public Optional<String> warning() {
        return warning;
    }

    /** Without a current version, the page shows the columns of the built-in definition. */
    @Override
    public List<String> findCurrentColumns() {
        return current.map(DefinitionVersion::definition).orElse(builtIn).workflow().columns();
    }

    /**
     * Anyone who can edit the status comment can change the version of the record. So the version must have a
     * definition copy: the factory never runs a definition that did not come from the definition folder.
     */
    @Override
    public FactoryDefinition definitionOf(IssueRecord record) {
        return record.definitionVersion().map(this::definitionOfVersion).orElse(builtIn);
    }

    private FactoryDefinition definitionOfVersion(String version) {
        FactoryDefinition known = definitionsByVersion.get(version);
        if (known != null) {
            return known;
        }
        String hash = findValidHash(version);
        if (!copies.exists(hash)) {
            throw new IllegalStateException("The version " + DefinitionVersionText.shorten(version)
                    + " of the record has no definition copy in " + copies.directory());
        }
        FactoryDefinition definition = loadCopy(version, hash);
        definitionsByVersion.put(version, definition);
        return definition;
    }

    private String findValidHash(String version) {
        if (!LocalVersion.isLocal(version)) {
            throw new IllegalStateException("The version " + DefinitionVersionText.shorten(version) + " of the "
                    + "record is a commit of .factory, but this worker reads the definition folder " + folder);
        }
        return LocalVersion.findHash(version).orElseThrow(() -> new IllegalStateException("The version "
                + DefinitionVersionText.shorten(version) + " of the record is not a valid local version"));
    }

    private FactoryDefinition loadCopy(String version, String hash) {
        try {
            return DefinitionLoader.load(new FolderDefinitionFiles(copies.folderOf(hash)));
        } catch (DefinitionException exception) {
            throw new IllegalStateException("The definition copy " + DefinitionVersionText.shorten(version)
                    + " does not load: " + exception.getMessage(), exception);
        }
    }

    private String describeFailure(String reason) {
        String start = "The definition folder " + folder + " does not load: " + describeReason(reason) + ". ";
        return current
                .map(version -> start + "The factory keeps the version "
                        + DefinitionVersionText.shorten(version.identifier()) + ".")
                .orElse(start + "The factory takes no new issue.");
    }

    /** One line without a final period, so that the reason fits in the sentence of the warning. */
    private static String describeReason(String message) {
        String trimmed = message.replaceAll("\\s*\\R\\s*", " ").strip();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
```

`GitDefinitionVersions` has the same private method `describeReason`. Move it to `DefinitionVersionText` as `public static String describeReason(String message)`, and call it from both classes. Delete the two private copies.

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='FolderDefinitionVersionsTest,GitDefinitionVersionsTest'`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Add the versions of a definition folder

Each version that loads gets a definition copy in the work folder.
An issue reads its definition only from its copy.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: The worker takes an issue with a local version

**Files:**
- Test: `src/test/.../worker/WorkerTest.java`

- [ ] **Step 1: Write the test**

Add this test to `WorkerTest.java`, after `givesTheIntakeTheCurrentCommit`. Add the imports `com.meterian.factory.definition.DefinitionCopies`, `com.meterian.factory.definition.FolderDefinitionVersions`, `com.meterian.factory.definition.FolderHash`, `com.meterian.factory.definition.LocalVersion`, and the static import `com.meterian.factory.testing.DefinitionFolders.writeDefinitionFolder`.

```java
    @Test
    void givesTheIntakeTheVersionOfTheDefinitionFolder() throws IOException {
        Path folder = writeDefinitionFolder(temporaryDirectory.resolve("definition"), mainFileFor("true"));
        FactoryConfiguration configuration = createConfiguration(0, Duration.ofSeconds(60));
        FolderDefinitionVersions versions = new FolderDefinitionVersions(folder,
                DefinitionCopies.inWorkFolder(configuration.workDirectory()), builtInWithBuild(buildCommand));
        Worker worker = createWorker(new FakeAgentRunner(), configuration, board, versions);

        worker.pollOnce();

        assertThat(readRecord(ISSUE).orElseThrow().definitionVersion())
                .contains(LocalVersion.of(FolderHash.calculate(folder)));
    }
```

If `WorkerTest` has no import of `java.io.IOException` or `java.nio.file.Path`, add them.

- [ ] **Step 2: Run the test**

Run: `./mvnw -q test -Dtest=WorkerTest#givesTheIntakeTheVersionOfTheDefinitionFolder`
Expected: PASS. The worker uses only the interface since Task 2, so no main code changes. If the test fails, the failure shows a place that still needs `GitDefinitionVersions`. Fix that place to use `DefinitionSource`.

- [ ] **Step 3: Commit**

```bash
git add -A src
git commit -m "Test that the worker gives a new issue the local version

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: The option --definition of run

**Files:**
- Create: `RunOptions.java`
- Test: `src/test/.../RunOptionsTest.java`
- Modify: `Main.java`
- Modify: `src/test/.../MainTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/meterian/factory/RunOptionsTest.java`:

```java
package com.meterian.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.setup.WorkerFileFinder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunOptionsTest {

    @TempDir
    Path temporaryDirectory;

    private static WorkerFileFinder refusingFinder() {
        return new WorkerFileFinder(Path.of("no-home"), () -> {
            throw new AssertionError("The command gives the file, so the finder must not run.");
        });
    }

    @Test
    void readsTheConfigurationFile() throws Main.StartupException {
        RunOptions options = RunOptions.parse(new String[] {"run", "--config", "worker.yaml"});

        assertThat(options.findConfigurationFile(refusingFinder())).isEqualTo(Path.of("worker.yaml"));
        assertThat(options.definitionFolder()).isEmpty();
    }

    @Test
    void findsTheConfigurationOfTheCloneWithoutTheOptionConfig() throws Exception {
        Path workerFile = Files.createDirectories(temporaryDirectory.resolve(".rossumworks/owner-project"))
                .resolve("worker.yaml");
        Files.writeString(workerFile, "repository: owner/project\n");
        WorkerFileFinder finder = new WorkerFileFinder(temporaryDirectory,
                () -> Optional.of(new RepositoryName("owner", "project")));

        RunOptions options = RunOptions.parse(new String[] {"run"});

        assertThat(options.findConfigurationFile(finder)).isEqualTo(workerFile);
    }

    @Test
    void readsTheDefinitionFolderInEachOrder() throws Main.StartupException {
        RunOptions first = RunOptions.parse(new String[] {"run", "--definition", "my-factory"});
        RunOptions second = RunOptions.parse(
                new String[] {"run", "--definition", "my-factory", "--config", "worker.yaml"});
        RunOptions third = RunOptions.parse(
                new String[] {"run", "--config", "worker.yaml", "--definition", "my-factory"});

        assertThat(first.definitionFolder()).contains(Path.of("my-factory"));
        assertThat(second.definitionFolder()).contains(Path.of("my-factory"));
        assertThat(third.configurationFile()).contains(Path.of("worker.yaml"));
    }

    @Test
    void refusesOtherArguments() {
        for (String[] arguments : List.of(new String[] {}, new String[] {"start", "--config", "worker.yaml"},
                new String[] {"run", "--config"}, new String[] {"run", "--definition"},
                new String[] {"run", "--file", "worker.yaml"}, new String[] {"run", "worker.yaml"},
                new String[] {"run", "--config", "a.yaml", "--config", "b.yaml"},
                new String[] {"run", "--definition", "a", "--definition", "b"})) {
            assertThatThrownBy(() -> RunOptions.parse(arguments)).hasMessage(RunOptions.USAGE);
        }
    }

    @Test
    void givesTheAbsolutePathOfAFolderThatExists() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));

        RunOptions options = RunOptions.parse(new String[] {"run", "--definition", folder.toString()});

        assertThat(options.verifyDefinitionFolder()).contains(folder.toAbsolutePath());
    }

    @Test
    void refusesAMissingDefinitionFolder() throws Main.StartupException {
        Path missing = temporaryDirectory.resolve("missing");
        RunOptions options = RunOptions.parse(new String[] {"run", "--definition", missing.toString()});

        assertThatThrownBy(options::verifyDefinitionFolder)
                .hasMessage("The definition folder " + missing.toAbsolutePath() + " does not exist.");
    }

    @Test
    void refusesADefinitionFolderThatIsAFile() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("file"), "text");
        RunOptions options = RunOptions.parse(new String[] {"run", "--definition", file.toString()});

        assertThatThrownBy(options::verifyDefinitionFolder)
                .hasMessage("The definition folder " + file.toAbsolutePath() + " is not a folder.");
    }

    @Test
    void givesNoFolderWithoutTheOption() throws Main.StartupException, IOException {
        assertThat(RunOptions.parse(new String[] {"run"}).verifyDefinitionFolder()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=RunOptionsTest`
Expected: a compilation failure: `cannot find symbol ... RunOptions`.

- [ ] **Step 3: Write the code**

Create `src/main/java/com/meterian/factory/RunOptions.java`:

```java
package com.meterian.factory;

import com.meterian.factory.Main.StartupException;
import com.meterian.factory.setup.WorkerFileFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** The arguments of the command run: --config <file> and --definition <folder>, in any order. */
public record RunOptions(Optional<Path> configurationFile, Optional<Path> definitionFolder) {

    public static final String USAGE = "Usage: java -jar rossumworks.jar run [--config <file>] "
            + "[--definition <folder>]";
    private static final String COMMAND = "run";
    private static final String CONFIG_OPTION = "--config";
    private static final String DEFINITION_OPTION = "--definition";

    public static RunOptions parse(String[] arguments) throws StartupException {
        if (arguments.length == 0 || !arguments[0].equals(COMMAND)) {
            throw new StartupException(USAGE);
        }
        Optional<Path> configurationFile = Optional.empty();
        Optional<Path> definitionFolder = Optional.empty();
        for (int index = 1; index < arguments.length; index += 2) {
            Path value = Path.of(readValue(arguments, index + 1));
            switch (arguments[index]) {
                case CONFIG_OPTION -> configurationFile = acceptOnce(configurationFile, value);
                case DEFINITION_OPTION -> definitionFolder = acceptOnce(definitionFolder, value);
                default -> throw new StartupException(USAGE);
            }
        }
        return new RunOptions(configurationFile, definitionFolder);
    }

    private static String readValue(String[] arguments, int index) throws StartupException {
        if (index >= arguments.length) {
            throw new StartupException(USAGE);
        }
        return arguments[index];
    }

    private static Optional<Path> acceptOnce(Optional<Path> earlier, Path value) throws StartupException {
        if (earlier.isPresent()) {
            throw new StartupException(USAGE);
        }
        return Optional.of(value);
    }

    /** The file of --config. Without it, the worker.yaml of the clone in the current folder. */
    public Path findConfigurationFile(WorkerFileFinder finder) throws StartupException {
        if (configurationFile.isPresent()) {
            return configurationFile.get();
        }
        return finder.find();
    }

    /** The absolute path of the folder of --definition, after a check that the folder exists. */
    public Optional<Path> verifyDefinitionFolder() throws StartupException {
        if (definitionFolder.isEmpty()) {
            return Optional.empty();
        }
        Path folder = definitionFolder.get().toAbsolutePath();
        if (!Files.exists(folder)) {
            throw new StartupException("The definition folder " + folder + " does not exist.");
        }
        if (!Files.isDirectory(folder)) {
            throw new StartupException("The definition folder " + folder + " is not a folder.");
        }
        return Optional.of(folder);
    }
}
```

Change `Main.java`:

1. Delete the method `readConfigurationPath`.
2. In `Main.run(..., WorkerFileFinder finder)`, replace the line `Path configurationFile = readConfigurationPath(arguments, finder);` with:

```java
            RunOptions options = RunOptions.parse(arguments);
            Path configurationFile = options.findConfigurationFile(finder);
            Optional<Path> definitionFolder = options.verifyDefinitionFolder();
```

3. Change the call of the starter to `workerStarter.start(configuration, provider, retries, token, errors, definitionFolder);`.
4. Change the interface `WorkerStarter`:

```java
    interface WorkerStarter {
        int start(FactoryConfiguration configuration, Provider provider, RetrySettings retries, String token,
                  PrintStream errors, Optional<Path> definitionFolder)
                throws InterruptedException, StartupException;
    }
```

5. Add the parameter `Optional<Path> definitionFolder` to `runWorker`, after `errors`. Replace the line `GitDefinitionVersions definitions = createDefinitionVersions(git, configuration);` with `DefinitionSource definitions = createDefinitionSource(git, configuration, definitionFolder);`.
6. Add this method before `createDefinitionVersions`:

```java
    /** With --definition, the definition folder. Else, the folder .factory of the default branch. */
    private static DefinitionSource createDefinitionSource(Git git, FactoryConfiguration configuration,
                                                           Optional<Path> definitionFolder)
            throws StartupException {
        if (definitionFolder.isPresent()) {
            return new FolderDefinitionVersions(definitionFolder.get(),
                    DefinitionCopies.inWorkFolder(configuration.workDirectory()), BuiltInDefinition.load());
        }
        return createDefinitionVersions(git, configuration);
    }
```

7. Change the constant `USAGE` of `Main`:

```java
    private static final String USAGE = RunOptions.USAGE + "\n"
            + "       java -jar rossumworks.jar hash-password\n"
            + "       java -jar rossumworks.jar create [--repository owner/name] [--work-directory <path>] "
            + "[--web <address:port> | --no-web] [--terminal-command \"<command>\"] [--build-command \"<command>\"] "
            + "[--definition <folder>] [--yes]";
```

The test `refusesToStartWithoutAUsableArgument` of `MainTest` expects the full `USAGE` of `Main` for a wrong command. `RunOptions.parse` gives only the usage of `run`. So in `Main.run`, read the options with this method:

```java
    /** A wrong command shows the usage of all the commands, not only of run. */
    private static RunOptions readRunOptions(String[] arguments) throws StartupException {
        try {
            return RunOptions.parse(arguments);
        } catch (StartupException exception) {
            throw new StartupException(USAGE);
        }
    }
```

Then the first line in step 2 above is `RunOptions options = readRunOptions(arguments);`.

8. Change the class Javadoc of `Main` to `/** The command line: run [--config <file>] [--definition <folder>], hash-password, or create. */`.

Change `MainTest.java`:

1. Delete the tests `readsTheConfigurationPath`, `findsTheConfigurationOfTheCloneWithoutTheOptionConfig`, and `refusesOtherArguments`, and the helper `refusingFinder` if no other test uses it. `RunOptionsTest` has these tests now.
2. Add the parameter `definitionFolder` to each lambda of `WorkerStarter`. Find them with `grep -n "errorStream) ->" src/test/java/com/meterian/factory/MainTest.java`. For example, `(configuration, provider, retries, token, errorStream) ->` becomes `(configuration, provider, retries, token, errorStream, definitionFolder) ->`.
3. Change the constant `USAGE` of `MainTest` to the new text of `Main.USAGE`.
4. Add this test:

```java
    @Test
    void givesTheDefinitionFolderToTheWorker() throws IOException {
        Path configuration = writeConfiguration("claude");
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        List<Optional<Path>> startedWithFolder = new ArrayList<>();

        int exitCode = Main.run(new String[] {"run", "--config", configuration.toString(), "--definition",
                        folder.toString()},
                createEnvironmentWithAllCommands(), errors,
                () -> {
                    throw new AssertionError("The test reached gh");
                },
                (loaded, provider, retries, token, errorStream, definitionFolder) -> {
                    startedWithFolder.add(definitionFolder);
                    return Main.EXIT_SUCCESS;
                }, createRecordingGit(new ArrayList<>()));

        assertThat(exitCode).isEqualTo(Main.EXIT_SUCCESS);
        assertThat(startedWithFolder).containsExactly(Optional.of(folder.toAbsolutePath()));
    }
```


- [ ] **Step 4: Run all the tests**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Add the option --definition to the command run

With the option, the worker reads the definition folder in place of
the folder .factory of the default branch.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: The default definition for a definition folder

**Files:**
- Modify: `setup/DefaultFactoryFolder.java`
- Test: `src/test/.../setup/DefaultFactoryFolderTest.java`

- [ ] **Step 1: Write the failing test**

Add this test to `DefaultFactoryFolderTest.java`:

```java
    @Test
    void givesTheDefinitionFilesWithoutTheFolderAndWithoutTheRunScript() {
        Map<String, String> repositoryFiles = DefaultFactoryFolder.files(RepositoryBuild.MAVEN, "main");

        Map<String, String> definitionFiles = DefaultFactoryFolder.definitionFiles(RepositoryBuild.MAVEN, "main");

        assertThat(definitionFiles).containsKey("factory.yaml").doesNotContainKey("run.sh");
        assertThat(definitionFiles.get("factory.yaml")).isEqualTo(repositoryFiles.get(".factory/factory.yaml"));
        assertThat(definitionFiles).hasSize(repositoryFiles.size() - 1);
    }
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw -q test -Dtest=DefaultFactoryFolderTest`
Expected: a compilation failure: `cannot find symbol ... definitionFiles`.

- [ ] **Step 3: Write the code**

In `DefaultFactoryFolder.java`, replace the method `files` with these two methods:

```java
    /** The files by their path relative to the repository, with their text. */
    public static Map<String, String> files(RepositoryBuild build, String targetBranch) {
        Map<String, String> files = new LinkedHashMap<>();
        definitionFiles(build, targetBranch).forEach((path, text) -> files.put(FOLDER + path, text));
        files.put(FOLDER + RUN_SCRIPT, readRunScript());
        return files;
    }

    /** The files of the definition by their path relative to the definition folder. No run script. */
    public static Map<String, String> definitionFiles(RepositoryBuild build, String targetBranch) {
        FactoryDefinition definition = BuiltInDefinition.load();
        Map<String, String> files = new LinkedHashMap<>();
        files.put(MAIN_FILE, adaptMainFile(readResource(MAIN_FILE), build, targetBranch));
        for (String promptPath : definition.prompts().keySet()) {
            files.put(promptPath, readResource(promptPath));
        }
        return files;
    }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='DefaultFactoryFolderTest,GeneratedFactoryFolderTest'`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Give the default definition files for a definition folder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: The definition folder of create

**Files:**
- Create: `setup/DefinitionFolderSetup.java`
- Test: `src/test/.../setup/DefinitionFolderSetupTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/meterian/factory/setup/DefinitionFolderSetupTest.java`:

```java
package com.meterian.factory.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.definition.DefinitionLoader;
import com.meterian.factory.definition.FolderDefinitionFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionFolderSetupTest {

    private static final Map<String, String> FILES = Map.of("factory.yaml", "version: 1\n",
            "prompts/implement.md", "Implement.\n");

    @TempDir
    Path temporaryDirectory;

    @Test
    void needsADefinitionForAMissingFolder() throws SetupException {
        DefinitionFolderSetup setup = new DefinitionFolderSetup(temporaryDirectory.resolve("missing"));

        assertThat(setup.needsDefinition()).isTrue();
    }

    @Test
    void needsADefinitionForAnEmptyFolder() throws Exception {
        DefinitionFolderSetup setup = new DefinitionFolderSetup(
                Files.createDirectories(temporaryDirectory.resolve("empty")));

        assertThat(setup.needsDefinition()).isTrue();
    }

    @Test
    void needsNoDefinitionWhenTheFolderHasTheMainFile() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.writeString(folder.resolve("factory.yaml"), "version: 1\n");

        assertThat(new DefinitionFolderSetup(folder).needsDefinition()).isFalse();
    }

    @Test
    void refusesAFolderWithOtherFilesAndNoMainFile() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.writeString(folder.resolve("notes.md"), "notes");

        assertThatThrownBy(() -> new DefinitionFolderSetup(folder).needsDefinition())
                .isInstanceOf(SetupException.class)
                .hasMessage("The definition folder " + folder + " has files, but no factory.yaml. "
                        + "Correct the folder, or give an empty folder, and run create again.");
    }

    @Test
    void refusesAPathThatIsAFile() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("file"), "text");

        assertThatThrownBy(() -> new DefinitionFolderSetup(file).needsDefinition())
                .isInstanceOf(SetupException.class)
                .hasMessage("The definition folder " + file + " is not a folder.");
    }

    @Test
    void writesTheFilesAndMakesTheFolder() throws Exception {
        Path folder = temporaryDirectory.resolve("new/definition");

        new DefinitionFolderSetup(folder).write(FILES);

        assertThat(folder.resolve("factory.yaml")).hasContent("version: 1");
        assertThat(folder.resolve("prompts/implement.md")).hasContent("Implement.");
    }

    @Test
    void neverOverwritesAFile() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.writeString(folder.resolve("factory.yaml"), "mine");

        assertThatThrownBy(() -> new DefinitionFolderSetup(folder).write(FILES))
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("Cannot write the definition folder " + folder);
        assertThat(folder.resolve("factory.yaml")).hasContent("mine");
    }

    @Test
    void writesTheDefaultDefinitionThatTheWorkerLoads() throws Exception {
        Path folder = temporaryDirectory.resolve("definition");
        new DefinitionFolderSetup(folder).write(DefaultFactoryFolder.definitionFiles(RepositoryBuild.MAVEN, "main"));

        assertThat(DefinitionLoader.load(new FolderDefinitionFiles(folder)).mergeTargetBranch()).isEqualTo("main");
    }
}
```


- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=DefinitionFolderSetupTest`
Expected: a compilation failure: `cannot find symbol ... DefinitionFolderSetup`.

- [ ] **Step 3: Write the code**

Create `src/main/java/com/meterian/factory/setup/DefinitionFolderSetup.java`:

```java
package com.meterian.factory.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.stream.Stream;

/** The definition folder of create --definition: the checks before the summary, and the write after it. */
public final class DefinitionFolderSetup {

    private static final String MAIN_FILE = "factory.yaml";

    private final Path folder;

    public DefinitionFolderSetup(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    /**
     * @return true when the folder is missing or empty, false when it has factory.yaml
     * @throws SetupException when the path is a file, or when the folder has other files but no factory.yaml
     */
    public boolean needsDefinition() throws SetupException {
        if (!Files.exists(folder)) {
            return true;
        }
        if (!Files.isDirectory(folder)) {
            throw new SetupException("The definition folder " + folder + " is not a folder.");
        }
        if (Files.isRegularFile(folder.resolve(MAIN_FILE))) {
            return false;
        }
        if (!isEmpty()) {
            throw new SetupException("The definition folder " + folder + " has files, but no factory.yaml. "
                    + "Correct the folder, or give an empty folder, and run create again.");
        }
        return true;
    }

    private boolean isEmpty() throws SetupException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        } catch (IOException exception) {
            throw new SetupException("Cannot read the definition folder " + folder + ": " + exception.getMessage());
        }
    }

    /** Writes each file. A file that exists stops the write, so the command never overwrites a file. */
    public void write(Map<String, String> files) throws SetupException {
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = folder.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException exception) {
            throw new SetupException("Cannot write the definition folder " + folder + ": " + exception.getMessage());
        }
    }
}
```


- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest=DefinitionFolderSetupTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "Add the checks and the write of the definition folder for create

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: The option --definition of create

**Files:**
- Modify: `setup/CreateOptions.java`, `setup/RepositorySetup.java`, `setup/RepositoryFacts.java`, `setup/CreateCommand.java`, `setup/SetupSummary.java`
- Test: `setup/CreateOptionsTest.java`, `setup/RepositorySetupTest.java`, `setup/CreateCommandTest.java`

- [ ] **Step 1: Write the failing test of the option**

Add this test to `CreateOptionsTest.java`:

```java
    @Test
    void readsTheDefinitionFolder() throws SetupException {
        CreateOptions options = CreateOptions.parse(List.of("--definition", "my-factory"));

        assertThat(options.definitionFolder()).contains(Path.of("my-factory"));
    }

    @Test
    void needsAValueForTheDefinitionFolder() {
        assertThatThrownBy(() -> CreateOptions.parse(List.of("--definition")))
                .hasMessageStartingWith("The parameter --definition needs a value.");
    }
```

- [ ] **Step 2: Add the option**

In `CreateOptions.java`:

1. Add the component `Optional<Path> definitionFolder` at the end of the record.
2. Add `[--definition <folder>]` to `USAGE`, before `[--yes]`.
3. In `parse`, add `Optional<Path> definitionFolder = Optional.empty();` and this case:

```java
                case "--definition" -> definitionFolder = Optional.of(Path.of(readValue(arguments, ++index, parameter)));
```

4. Give `definitionFolder` to the constructor at the end of `parse`.

Fix each other place that calls the constructor of `CreateOptions`. Find them with `grep -rn "new CreateOptions(" src`. Give `Optional.empty()` for the new component.

Run: `./mvnw -q test -Dtest=CreateOptionsTest`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Move the check of .factory out of RepositorySetup**

With `--definition`, the check of a folder `.factory` without `factory.yaml` must not occur. So `RepositorySetup.read` gives the fact, and `CreateCommand` makes the check.

Change `RepositoryFacts.java` to:

```java
package com.meterian.factory.setup;

import java.util.function.Predicate;

/** What the command knows about the newest commit of the default branch. */
public record RepositoryFacts(String defaultBranch, String baseCommit, boolean hasFactory, boolean hasFactoryFolder,
                              Predicate<String> hasFile) {

    /** A folder .factory without factory.yaml is not a definition that the worker can load. */
    public void verifyFactoryFolderIsComplete() throws SetupException {
        if (hasFactoryFolder && !hasFactory) {
            throw new SetupException("The default branch has a folder .factory without factory.yaml. "
                    + "Correct the folder, and run create again.");
        }
    }
}
```

Change `RepositorySetup.readFacts` to:

```java
    private RepositoryFacts readFacts(Path clone) {
        String defaultBranch = obtainDefaultBranch(clone);
        String baseCommit = git.commitOf(clone, "origin/" + defaultBranch);
        boolean hasFactory = git.hasPath(clone, baseCommit, MAIN_FILE);
        boolean hasFactoryFolder = git.hasPath(clone, baseCommit, FACTORY_FOLDER);
        return new RepositoryFacts(defaultBranch, baseCommit, hasFactory, hasFactoryFolder,
                path -> git.hasPath(clone, baseCommit, path));
    }
```

`obtainDefaultBranch` throws `SetupException`, so keep `throws SetupException` on `readFacts` if the compiler needs it.

In `RepositorySetupTest.java`, the test `refusesAFactoryFolderWithoutTheMainFile` calls `setup.read(clone)`. Change the call to `setup.read(clone).verifyFactoryFolderIsComplete()`. The expected message stays the same.

Fix each other place that calls `new RepositoryFacts(`. Find them with `grep -rn "new RepositoryFacts(" src`.

- [ ] **Step 4: Write the failing tests of the command**

Add these tests to `CreateCommandTest.java`. They use its helpers `pushToMain`, `runCreate`, `workerFile`, `outputText`, and `errorText`, and the console `FakeSetupConsole.withoutTerminal()`:

```java
    @Test
    void writesTheDefinitionFolderAndOpensNoPullRequest() throws IOException {
        pushToMain("package.json", "{}\n");
        Path folder = temporaryDirectory.resolve("my-factory");

        int exitCode = runCreate(FakeSetupConsole.withoutTerminal(), "--definition", folder.toString(),
                "--no-web", "--yes");

        assertThat(exitCode).isEqualTo(Main.EXIT_SUCCESS);
        assertThat(folder.resolve("factory.yaml")).exists();
        assertThat(folder.resolve("run.sh")).doesNotExist();
        assertThat(gitHub.pullRequests()).isEmpty();
        assertThat(outputText()).contains("java -jar ").contains(" run --config ")
                .contains(" --definition " + folder.toAbsolutePath());
    }

    @Test
    void keepsADefinitionFolderWithTheMainFile() throws IOException {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("my-factory"));
        Files.writeString(folder.resolve("factory.yaml"), "mine");

        int exitCode = runCreate(FakeSetupConsole.withoutTerminal(), "--definition", folder.toString(),
                "--no-web", "--yes");

        assertThat(exitCode).isEqualTo(Main.EXIT_SUCCESS);
        assertThat(folder.resolve("factory.yaml")).hasContent("mine");
        assertThat(workerFile()).exists();
    }

    @Test
    void stopsOnADefinitionFolderWithOtherFiles() throws IOException {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("my-factory"));
        Files.writeString(folder.resolve("notes.md"), "notes");

        int exitCode = runCreate(FakeSetupConsole.withoutTerminal(), "--definition", folder.toString(),
                "--no-web", "--yes");

        assertThat(exitCode).isEqualTo(Main.EXIT_FAILURE);
        assertThat(errorText()).contains("has files, but no factory.yaml");
        assertThat(workerFile()).doesNotExist();
    }

    @Test
    void ignoresAnIncompleteFactoryFolderOfTheRepositoryWithADefinitionFolder() {
        pushToMain("package.json", "{}\n");
        pushToMain(".factory/notes.md", "notes\n");
        Path folder = temporaryDirectory.resolve("my-factory");

        int exitCode = runCreate(FakeSetupConsole.withoutTerminal(), "--definition", folder.toString(),
                "--no-web", "--yes");

        assertThat(exitCode).isEqualTo(Main.EXIT_SUCCESS);
        assertThat(folder.resolve("factory.yaml")).exists();
    }
```

- [ ] **Step 5: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=CreateCommandTest`
Expected: the new tests fail. `create` opens a pull request, and it does not write the definition folder.

- [ ] **Step 6: Change the command**

In `SetupSummary.java`, add this method:

```java
    /** The summary when the command writes worker.yaml and the definition folder. */
    static String describe(WorkerSettings settings, RepositoryBuild build, Path definitionFolder,
                           Map<String, String> files) {
        StringBuilder text = new StringBuilder(describe(settings));
        text.append("Build command: ").append(build.command()).append('\n');
        text.append("The command writes these files in the definition folder ").append(definitionFolder)
                .append(":\n");
        files.keySet().forEach(path -> text.append("  ").append(path).append('\n'));
        return text.toString();
    }
```

In `CreateCommand.java`:

1. In `create`, after the line `RepositoryFacts facts = setup.read(clone);` and the line that makes `settings`, add:

```java
        if (options.definitionFolder().isPresent()) {
            DefinitionFolderSetup definitionFolder =
                    new DefinitionFolderSetup(options.definitionFolder().get().toAbsolutePath());
            return createWithDefinitionFolder(options, questions, settings, facts, definitionFolder);
        }
        facts.verifyFactoryFolderIsComplete();
```

2. Add these methods:

```java
    /** The definition stays outside the repository: the command writes the folder and opens no pull request. */
    private int createWithDefinitionFolder(CreateOptions options, SetupQuestions questions,
                                           WorkerSettings settings, RepositoryFacts facts,
                                           DefinitionFolderSetup definitionFolder) throws SetupException {
        if (!definitionFolder.needsDefinition()) {
            output.println("The definition folder " + definitionFolder.folder() + " has a factory.yaml. "
                    + "The command keeps the folder.");
            return writeOnlyTheWorkerFile(options, questions, settings, Optional.of(definitionFolder.folder()));
        }
        RepositoryBuild build = questions.askBuild(options, BuildFinder.find(facts.hasFile()),
                BuildFinder.isJavaRepository(facts.hasFile()));
        Map<String, String> files = DefaultFactoryFolder.definitionFiles(build, facts.defaultBranch());
        GeneratedFactoryFolder.verifyLoads(DefaultFactoryFolder.files(build, facts.defaultBranch()));
        String summary = SetupSummary.describe(settings, build, definitionFolder.folder(), files);
        if (!questions.confirm(options, summary)) {
            showNothingWritten();
            return Main.EXIT_SUCCESS;
        }
        writeWorkerFile(settings);
        definitionFolder.write(files);
        copyRunningJarToCache();
        output.println("The command wrote the file " + settings.configurationFile() + ".");
        output.println("The command wrote the definition folder " + definitionFolder.folder() + ".");
        showStartCommandWithDefinitionFolder(settings, definitionFolder.folder());
        return Main.EXIT_SUCCESS;
    }

    /** Outside a clone, run cannot find worker.yaml, so the command always has --config. */
    private void showStartCommandWithDefinitionFolder(WorkerSettings settings, Path definitionFolder) {
        output.println("Start the worker with this command:");
        output.println("  java -jar " + findJarPath() + " run --config " + settings.configurationFile()
                + " --definition " + definitionFolder);
    }
```

3. Change `writeOnlyTheWorkerFile`:
   - Move its first line, `output.println("The repository already has a .factory. The command opens no pull request.");`, to the caller in `create`, before the call. The message is not true for a definition folder.
   - Add the parameter `Optional<Path> definitionFolder` at the end.
   - When the folder is present, the method prints `"The command wrote the file " + settings.configurationFile() + "."` and calls `showStartCommandWithDefinitionFolder(settings, definitionFolder.get())`. It does not call `showResult(...)`.
   - The old call in `create` gives `Optional.empty()`.

The method `GeneratedFactoryFolder.verifyLoads` needs the keys with `.factory/`, so the code gives it the files of `DefaultFactoryFolder.files`. Both maps have the same definition.

4. Change the class Javadoc to `/** The command create: it asks the questions, writes worker.yaml, and opens the PR factory/setup or writes the definition folder. */`.

- [ ] **Step 7: Run all the tests**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add -A src
git commit -m "Add the option --definition to the command create

With the option, create writes the default definition to the folder
and opens no pull request on the repository.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: The documents

**Files:**
- Modify: `docs/factory-definition.md`, `docs/getting-started.md`, `README.md`

The glossary already has the terms "definition folder" and "definition copy". Write all the text in Simplified Technical English, and use the terms of `docs/glossary.md`.

- [ ] **Step 1: Add a section to factory-definition.md**

Add a section `## 15. A definition outside the repository` at the end of `docs/factory-definition.md`. If a section 15 exists, use the next number. The section has this content:

```markdown
## 15. A definition outside the repository

You can keep the definition in a definition folder outside the repository. Give the folder with the option
`--definition` of `run`:

    java -jar rossumworks.jar run --definition <folder>

The definition folder has the same content as `.factory`: `factory.yaml` and the prompt files. It does not need
git. Without `--definition`, the worker reads `.factory` of the default branch.

### 15.1 The versions

At the start of each poll, the worker reads the definition folder. When the content changes, the worker makes a
new version. The version is `local:` and the SHA-256 hash of the content, for example `local:8be41d0`.

The worker keeps each version that loads as a definition copy in `<work folder>/definitions/<hash>/`. The
status comment of an issue keeps its version. The issue reads its definition from its copy, never from the
definition folder. So an edit of the folder reaches only the new issues. To give an edit to an issue in work, use
Start again.

The worker never deletes a definition copy.

### 15.2 When the definition folder does not load

- The worker keeps the last version that loads, and the board shows the reason at the top.
- After a restart, the worker takes the newest definition copy that loads.
- When no version loads, the worker takes no new issue.

A symbolic link in the definition folder is an error. The worker does not read the folder until you remove the
link.

### 15.3 An edit that is not complete

The worker can read the folder while your edit is not complete. When the half-done content loads, new issues
can start with it. To prevent this, edit a copy of the folder. When the copy is complete, rename it to the
name of the definition folder.

### 15.4 The checks of the status comment

A person with write access to the repository can edit the status comment. So the worker checks the version of
each record:

| Mode of the worker | Version in the record | What the worker does |
|---|---|---|
| Without `--definition` | A commit | It uses the commit when the commit is on the default branch. |
| Without `--definition` | A `local:` version | It blocks the issue. |
| With `--definition` | A `local:` version | It uses the version when its definition copy exists. |
| With `--definition` | A commit | It blocks the issue. |

When another person takes over the issue with their own factory, their machine has no copy. Their worker then
blocks the issue.

With `--definition`, the merge gate still has the event `factory-changed`, but the worker does not read the
`.factory` of the repository.
```

Write the code blocks with three backticks, not with indentation.

- [ ] **Step 2: Change getting-started.md**

1. Add a row to the table "The parameters of `create`", before `--yes`:

```markdown
| `--definition <folder>` | The definition folder. `create` writes the default definition there, and opens no pull request. See [Factory definition](factory-definition.md), section 15. | No folder: `create` opens the pull request `factory/setup`. |
```

2. Add these rows to the table "Special situations of `create`":

```markdown
| With `--definition`, the folder has `factory.yaml` | It keeps the folder, and writes only `worker.yaml`. |
| With `--definition`, the folder has other files, but no `factory.yaml` | It stops, and writes no file. |
| With `--definition`, the path is a file | It stops, and writes no file. |
```

3. In the section "The configuration file", add this paragraph at the end:

```markdown
With a definition folder, give it with `--definition`, for example
`java -jar <path of the jar> run --config <work folder>/worker.yaml --definition <folder>`. The order of the
options has no effect.
```

- [ ] **Step 3: Change README.md**

In the section "The workflow of an issue", after the sentence "The folder `.factory` of your repository holds the definition of **the workflow**.", add:

```markdown
You can also keep the definition in a folder outside the repository. See
[Factory definition](docs/factory-definition.md), section 15.
```

- [ ] **Step 4: Check the links**

Run: `grep -n "section 15" README.md docs/*.md`
Expected: each link points to the new section.

- [ ] **Step 5: Commit**

```bash
git add README.md docs
git commit -m "Describe the definition folder in the documents

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: The last check

- [ ] **Step 1: Run the full build**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, with no test failure.

- [ ] **Step 2: Look for old names**

Run: `grep -rn "DefinitionVersions\b\|definitionCommit\|withDefinitionCommit\|readConfigurationPath\|folder:" src docs --include=*.java --include=*.md | grep -v "JsonProperty(\"definitionCommit\")" | grep -v "docs/superpowers"`
Expected: no line in the main code. A line in an old test name is acceptable, for example `writesAndReadsTheStateTheActionAndTheDefinitionCommit`.

- [ ] **Step 3: Try the command by hand**

Run these commands. They show the usage and the check of a missing folder. They do not start a worker:

```bash
./mvnw -q -DskipTests package
java -jar target/rossumworks-*.jar run --definition /no/such/folder; echo "exit $?"
```

Expected: a message that names the configuration file or the definition folder, and `exit 1`. The worker reads `worker.yaml` before it checks the folder, so a missing `worker.yaml` gives its own message first.
