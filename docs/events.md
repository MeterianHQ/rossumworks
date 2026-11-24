# Events

The worker writes a log of what it does to the file `<work folder>/events.jsonl`. Each line of this log is a **log
event**. A log event is not the same as the event of an action, which the workflow uses. This page tells how you
read the file, and what each log event contains.

The file is for a program that watches the factory, for example a script or a Claude Code session. It is
separate from the log of the worker.

## The file

- The file uses the JSON Lines format: each line is one complete JSON object for one log event.
- The worker writes each line in one write operation, so a reader never sees half a line.
- The file keeps the lines of the earlier runs, so a reader finds the history after a restart.
- The file has no secret and no prompt text.
- A failure to write the file does not stop the worker. The log of the worker gets a warning.
- The worker does not rotate the file.

To watch the file, use `tail -F` and a filter on the field `type`, for example:

```
tail -F <work folder>/events.jsonl | jq -c 'select(.type == "move" or .type == "blocked")'
```

## The fields

Each line has the fields `time` (UTC, ISO 8601) and `type`. Each line of an issue also has the field `issue`.
The other fields depend on the type:

| `type` | When | Fields |
|---|---|---|
| `worker` | The worker starts or stops. | `event` (`start`, `stop`), `version`, `repository` |
| `move` | An issue moves to a state. | `from`, `event`, `to`, `column`, `entries` |
| `action-end` | An action ends. | `state`, `action`, `result`, `duration_s`, and the fields of the action |
| `pr` | The factory opens or merges a pull request. | `event` (`opened`, `merged`), `url`, `commit` (for `merged`, when the worker knows it) |
| `blocked` | An issue goes to the [block state](glossary.md). | `reason` |
| `question` | The agent asks you a question. | `first_line` |
| `cost` | A session ends with a cost. Only with an API key. | `cost_usd` |
| `wait` | The worker waits for a limit. | `reason` (`github rate limit`, `usage limit`, `daily budget`), `until` (UTC) |

More about some fields:

- **`move`:** the worker can move an issue without an event of an action, for example at a limit or at a block. Then
  the line has no field `event`. The field `entries` gives the count and the limit of each state with a limit, for example
  `{"implement": "1/3", "review": "0/10"}`. An entry counts when the action of the state starts.
- **`action-end`, `result`:** the event of the action. It is `blocked` when the action blocks the
  issue. It is `moved` when the action moves the issue without an event. It is `error` when the action fails with
  an exception. An action that waits, for example for the usage limit, writes no line.
- **`action-end`, `duration_s`:** the time of the last run of the action, in seconds. An action that waits for
  you, for example an approval, runs at each poll, so its `duration_s` is near 0. It is not the time of the wait.
- **`blocked` and `question`:** the field has only the first line of the note or of the question.

### The fields of each action

| `action` | Fields of `action-end` |
|---|---|
| `coding` | `prompt` (`implement`, `fix`, `continue`, `resume`) |
| `build` | `exit_code`, when the build command ends by itself |
| `reviews` | `reviews`: one object for each review, for example `{"correctness": {"verdict": "approve", "findings": 0}}` |
| `risk` | `risk`, `small`, `reasons` (the first reason only) |
| `merge-gate` | `reason`, for the events `needs-approval` and `factory-changed`. See [Factory definition](factory-definition.md). |

## Examples

```
{"time":"2026-11-22T17:20:04Z","type":"pr","issue":39,"event":"opened","url":"https://github.com/MeterianHQ/oss-detect/pull/43"}
{"time":"2026-11-22T17:20:05Z","type":"move","issue":39,"from":"build","event":"passed","to":"review","column":"in-review","entries":{"implement":"1/3","review":"0/10"}}
{"time":"2026-11-22T17:31:40Z","type":"action-end","issue":39,"state":"review","action":"reviews","result":"rejected","duration_s":612,"reviews":{"correctness":{"verdict":"approve","findings":0},"security":{"verdict":"reject","findings":2}}}
```

## Repeated lines

The worker writes a line when the thing occurs, before it writes the status comment to GitHub. When the write to
GitHub fails, the worker runs the action again, so a line can repeat. To remove the repeats, compare `issue`,
`type`, and the other fields of the lines.
