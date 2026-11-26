# External definition design

## 1. Purpose

You can keep the definition of a factory outside the repository. The option `--definition <folder>` of the
commands `run` and `create` names a **definition folder**. The folder can be anywhere on the machine, and it
does not need git.

Without `--definition`, the factory works the same as now: the worker reads `.factory` from the default branch.

## 2. Decisions

| Subject | Decision | Reason |
|---|---|---|
| Where the location comes from | A command-line option, not a key of `worker.yaml` | Your choice. |
| The content of the definition folder | The same as `.factory`: `factory.yaml` and the prompt files | The loader and the documents do not change. |
| Git in the definition folder | Not necessary | The location and the tools of the folder are your choice. |
| The version of a definition folder | The SHA-256 hash of its content | A folder has no commits. The same content always gives the same version. |
| The copies | The worker copies each version that loads to the work folder | An issue in work keeps its version, also after a restart. |
| `run.sh` with `create --definition` | `create` does not write it | The script goes to the parent folder to find the clone. That works only for `.factory` in a clone. |

## 3. The command `run`

These forms are valid:

```
java -jar rossumworks.jar run
java -jar rossumworks.jar run --config <file>
java -jar rossumworks.jar run --definition <folder>
java -jar rossumworks.jar run --config <file> --definition <folder>
```

The options can come in any order. Each option can occur one time. The worker converts `<folder>` to an
absolute path at the start.

The worker does not start when `<folder>` does not exist or is not a folder. The message names the path. A folder
with a definition that does not load is not a reason to stop: section 5 applies.

## 4. The versions of a definition folder

### 4.1 The hash

The worker reads all the regular files of the definition folder and of its subfolders. It sorts them by their
relative path. The hash is the SHA-256 of each relative path and each content, in that order. A file of zero
bytes counts. Another type of entry, for example a symbolic link, makes the read fail with a message that names
the entry.

The version of a definition folder is `local:` and the 64 hexadecimal characters of the hash. The prefix makes
the difference from a commit clear in the status comment and in the log. The board and the notes show `local:`
and the first 7 characters of the hash.

### 4.2 The copies

Each version that loads has a **definition copy** in `<work folder>/definitions/<hash>/`. The worker writes the
copy to a temporary folder in `definitions`, and then renames it. So a copy is always complete. The worker
never changes or deletes a copy.

An issue reads its definition only from its copy, never from the definition folder.

## 5. The poll

At the start of each poll, the worker reads the definition folder and calculates the hash.

| Situation | What the worker does |
|---|---|
| The hash is the same as at the last poll | Nothing. |
| The hash is new, and the definition loads | It writes the copy, if it does not exist. The version becomes the current version, and the warning goes away. |
| The hash is new, and the definition does not load | It keeps the current version. The board shows the reason at the top. The log gets the reason one time. |
| The read of the folder fails | It keeps the current version. The board shows the reason at the top. The next poll tries again. |

When the worker starts and the definition folder does not load, the worker reads its copies. It takes the copy
with the newest modification time that loads. When no copy loads, the worker takes no new issue, the same as
now.

The worker can read the folder while your edit is not complete. The documents tell you how to prevent it: edit a
copy of the folder, and then rename it.

## 6. The record

A new issue gets the current version, and its status comment keeps it. The Java field
`IssueRecord.definitionCommit` becomes `definitionVersion`, because the value is a commit or a `local:` version.
The key in the status comment stays `definitionCommit`, so the old records and the factories of an older release
read it.

The worker checks the value of the record before it uses it:

| Mode of the worker | Value of the record | Check |
|---|---|---|
| Without `--definition` | A commit | It is on the default branch, the same as now. |
| Without `--definition` | A `local:` version | Refused. |
| With `--definition` | A `local:` version | Its copy exists. |
| With `--definition` | A commit | Refused. |
| Both | No value | The built-in definition, the same as now. |

A refused value or a missing copy blocks the issue, with a note that gives the reason. This also applies after a
takeover by the factory of another person who has no copy. A person who edits the status comment can thus select
only a version that came from your definition folder.

Start again gives the issue the current version, the same as now.

## 7. The merge gate

The check `factory-changed` stays as it is. With `--definition`, the worker never reads a `.factory` of the
repository, so this check protects nothing. But it costs nothing, and it keeps one behavior for both modes.

## 8. The command `create`

`create --definition <folder>` does the same steps as now, with these differences:

| Step | With `--definition` |
|---|---|
| The check of `.factory` on the default branch | It does not occur. |
| The build command | `create` asks for it when the definition folder has no `factory.yaml`. |
| The definition | `create` writes the default definition to the definition folder, with the build command and the target branch of the repository. It makes the folder when it does not exist. It does not write `run.sh`. |
| The pull request `factory/setup` | It does not occur. `create` pushes nothing to the repository. |
| The last message | The full command with absolute paths: `java -jar <jar> run --config <work folder>/worker.yaml --definition <folder>`. |

`create` writes the definition folder after `worker.yaml`, at the place of the pull request now.

| Situation | What `create` does |
|---|---|
| The definition folder has `factory.yaml` | It keeps the folder, and writes nothing in it. It does not ask for the build command. |
| The definition folder has other files, but no `factory.yaml` | It stops before the summary, and writes nothing. |
| The path exists and is not a folder | It stops before the summary, and writes nothing. |

`create` never overwrites a file. The parameter is valid also without a terminal.

## 9. The code

| Class | Change |
|---|---|
| `DefinitionLookup` | No change. |
| `DefinitionSource` | New interface: `refresh()`, `current()`, `warning()`, and the methods of `DefinitionLookup`. `Worker`, `StartAgainRequests`, `IssueWorkflow`, and the web server use it in place of `DefinitionVersions`. |
| `DefinitionVersions` | Renamed `GitDefinitionVersions`. It implements `DefinitionSource`. It refuses a `local:` version. |
| `FolderDefinitionVersions` | New. It implements `DefinitionSource` for a definition folder, with sections 4 to 6. |
| `FolderHash` | New. It calculates the version of a folder (section 4.1). |
| `DefinitionCopies` | New. It writes, finds, and lists the copies in `<work folder>/definitions`. |
| `FolderDefinitionFiles` | New. It implements `DefinitionFiles` for a folder on the disk, with the same check of the paths. |
| `RunOptions` | New. It reads the arguments of `run`. `Main.readConfigurationPath` moves into it. |
| `Main` | It makes a `FolderDefinitionVersions` when `run` has `--definition`. |
| `CreateOptions`, `RepositorySetup`, `CreateCommand` | The parameter `--definition`, and section 8. |
| `IssueRecord` | The field `definitionCommit` becomes `definitionVersion`. The key in the status comment does not change. |

## 10. The tests

- `FolderHash`: the same content in two folders gives the same hash. A change of a content, of a path, or a new
  empty file gives a new hash. A symbolic link fails.
- `DefinitionCopies`: a copy is complete after a write. A second write of the same version changes nothing.
- `FolderDefinitionVersions`: each row of the tables of sections 5 and 6, and the start with copies only.
- `RunOptions`: the four valid forms, an option two times, an unknown option, an option without a value.
- `CreateOptions` and `RepositorySetup`: each row of the tables of section 8.
- `MainTest`: a worker with `--definition` takes an issue and records a `local:` version.

## 11. The documents

| Document | Change |
|---|---|
| `glossary.md` | New terms: definition folder, definition copy. The term definition gets the definition folder. |
| `factory-definition.md` | A new section "A definition outside the repository": sections 3 to 7 of this design, and the advice about an edit that is not complete. |
| `getting-started.md` | The parameter `--definition` of `create`, its special situations, and the forms of `run`. |
| `README.md` | One sentence in "The workflow of an issue", with a link to the new section. |
