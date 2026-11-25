# Tutorial, Plan 2: the Lessons and the Publication — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Five optional lessons with their issues, proved by live runs, and the publication of the tutorial.

**Architecture:** Each lesson is a folder of issue files in the template, and a section of `docs/tutorial.md`. The issue script finds the folders, so it does not change. The factory code does not change.

**Tech Stack:** Markdown, bash, `gh`.

**Spec:** `docs/superpowers/specs/2026-11-24-tutorial-design.md`

**Locations:** `SAMPLE` is the clone of `MeterianHQ/rossumworks-tutorial`. `FACTORY` is the worktree `.worktree/tutorial-design` of `rossumworks`. Run each command in the root of the repository of its task.

**Rules:** All texts use Simplified Technical English, with the rules of section 6.5 of the spec. Commands for the user are short lines at the left edge, outside numbered lists. A push, a merge, and a change of visibility need the agreement of the owner. Commit messages end with the attribution lines of the session.

**Order:** Tasks 1 to 6 need no other work. Task 7 (`live-mode`) waits for issue #48 and does not stop Task 6. Thus the publication can occur before `live-mode`.

## Evidence from the trials of 2026-11-25

| Lesson | Trial | Result |
|---|---|---|
| `block` | Block and Continue | Pass. Block took 6 seconds. "Start again" has no test. |
| `approval` | `mode: always-ask` | Pass. The merge came 15 seconds after the approval. |
| `factory-change` | A rule in `review-quality.md` | Pass. The event was `factory-changed`. |
| `parallel` | Two discounts | Pass. One conflict, and a second approval for its PR. |
| `live-mode` | `/factory:continue` only | No test of the question of the agent. |

---

### Task 1: The lesson `block` in the spec

**Files:**
- Modify: `FACTORY/docs/superpowers/specs/2026-11-24-tutorial-design.md`

- [ ] **Step 1: Change section 5**

Replace the sentence that starts with "The command is" with:

```markdown
The command is `tutorial/create-issues.sh <lesson>`. The lessons are `first-fix`, `block`, `approval`,
`factory-change`, `parallel`, and `live-mode`.
```

- [ ] **Step 2: Add this row as the first row of the table in section 6.2**

```markdown
| `block` | A new output line in `CheckoutCommand` | Push "Block", "Continue", "Block", and "Start again" on the card. | "Continue" keeps the work. "Start again" discards it. |
```

- [ ] **Step 3: In the row of `live-mode`, replace the two occurrences of "push Enter" / "pushes Enter" with the button name**

The board has no button "Enter". Write "push Terminal while the action `coding` runs" and "after the user pushes Terminal".

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers
git commit -m "Add the lesson block to the tutorial design"
```

---

### Task 2: A test of the issue files

**Files:**
- Create: `SAMPLE/tutorial/test/test-issue-files.sh`

- [ ] **Step 1: Write the test**

```bash
#!/usr/bin/env bash
# Tests the format of the issue files: tutorial/test/test-issue-files.sh
set -euo pipefail

TUTORIAL_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly TUTORIAL_DIRECTORY
readonly LESSONS=(first-fix block approval factory-change parallel)

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

verify_lesson_has_issues() {
    compgen -G "${TUTORIAL_DIRECTORY}/issues/$1/*.md" > /dev/null || fail "the lesson $1 has no issue"
}

verify_format() {
    [[ -n "$(sed -n 1p "$1")" ]] || fail "$1 has no title"
    [[ -z "$(sed -n 2p "$1")" ]] || fail "$1 has no empty line after the title"
    [[ -n "$(sed -n 3p "$1")" ]] || fail "$1 has no body"
}

verify_titles_are_unique() {
    local duplicates
    duplicates=$(for file in "${TUTORIAL_DIRECTORY}"/issues/*/*.md; do head -n 1 "${file}"; done | sort | uniq -d)
    [[ -z "${duplicates}" ]] || fail "more than one issue has the title: ${duplicates}"
}

for lesson in "${LESSONS[@]}"; do
    verify_lesson_has_issues "${lesson}"
done
for file in "${TUTORIAL_DIRECTORY}"/issues/*/*.md; do
    verify_format "${file}"
done
verify_titles_are_unique
echo "ok"
```

- [ ] **Step 2: Run it, and see it fail**

```bash
chmod +x tutorial/test/test-issue-files.sh
tutorial/test/test-issue-files.sh
```

Expected: `FAIL: the lesson block has no issue`, exit code 1.

---

### Task 3: The issue files

**Files:**
- Create: the five files below in `SAMPLE/tutorial/issues`

- [ ] **Step 1: Write `block/show-the-number-of-items.md`**

```markdown
Show the number of items

The program shows the total and the payment. It must also show the number of items.

- The first line of the output is `Items: ` and the number of items.
- The number of items is the sum of the quantities of all the lines.

An example: the cart has 2 books and 3 pens. The first line is `Items: 5`.

Change the tests to the new output, and add a test with this example.
```

- [ ] **Step 2: Write `approval/lower-the-highest-payment.md`**

```markdown
Accept payments up to 500.00 only

The payment rule accepts an amount up to 1000.00. The highest accepted amount must be 500.00.

- The payment rule accepts 500.00.
- The payment rule refuses 500.01.

Change the tests to the new amount.
```

- [ ] **Step 3: Write `factory-change/check-the-names-of-the-tests.md`**

```markdown
Check the names of the tests in the quality review

Change the prompt of the quality review in `.factory/prompts/review-quality.md`.

Add one rule to the end of the list of rules: Reject a test whose name does not tell the expected behavior.

Do not change other files.
```

- [ ] **Step 4: Write `parallel/discount-a-large-cart.md`**

```markdown
Add a discount for a large cart

When the subtotal is 100.00 or more, `calculateTotal` subtracts 5.00 from the total.

- The subtotal is the price before discounts.
- Apply this discount after the discount in percent.

An example: a subtotal of 100.00 without a discount in percent costs 95.00. Add a test with this example.
```

- [ ] **Step 5: Write `parallel/discount-many-items.md`**

```markdown
Add a discount for many items

When the cart has 10 items or more, `calculateTotal` subtracts 1.00 from the total.

- Count the quantity of each line.
- Apply this discount after the discount in percent.
- The total is never less than 0.00.

An example: 10 pens for 2.00 each cost 19.00. Add a test with this example.
```

- [ ] **Step 6: Run the tests**

```bash
tutorial/test/test-issue-files.sh
tutorial/test/test-create-issues.sh
shellcheck tutorial/create-issues.sh tutorial/test/*.sh
./mvnw verify
```

Expected: `ok`, ten `ok test_...` lines, no finding, and `BUILD SUCCESS`.

- [ ] **Step 7: Commit, and push after the agreement of the owner**

```bash
git add tutorial
git commit -m "Add the issues of the lessons"
```

---

### Task 4: The text of the lessons

**Files:**
- Modify: `FACTORY/docs/tutorial.md`

- [ ] **Step 1: Correct the long line of "Set up the factory"**

Replace step 3 with:

```markdown
3. Push Enter at each question to take the default answer, but not at the confirmation. For an API key, type
   `anthropic-api-key` at the question about the provider, and then type a daily budget.
```

- [ ] **Step 2: Insert this text before "## Problems"**

````markdown
## Optional lessons

Each lesson needs only the core. You can do the lessons in any order, and you can stop after any lesson. Keep the
worker in operation. Run the commands in the second terminal, in the folder of the clone.

**Warning: each lesson uses your subscription, or costs money with an API key.**

| Lesson | What you learn |
|---|---|
| [`block`](#lesson-block) | Stop the work on an issue, and continue it |
| [`approval`](#lesson-approval) | Make each merge wait for you |
| [`factory-change`](#lesson-factory-change) | Change the factory with an issue |
| [`parallel`](#lesson-parallel) | Give two issues to the factory together |

### Lesson `block`

**Purpose.** You stop the work on an issue. Then you continue the work, or you start again.

**Steps.** "Terminal" is near "Block". Read the name of the button before you push it. Create the issue:

```
tutorial/create-issues.sh block
```

1. Add the label `factory` to the issue.
2. Wait until the card is in "In progress".
3. Push "Block" on the card at once, and confirm.
4. Wait until the card is in "Blocked".
5. Push "Continue". The agent continues its work.
6. Push "Block" again when the card is in "In progress", and confirm.
7. Wait until the card is in "Blocked".
8. Push "Start again", and confirm. The factory discards the work and starts a new agent.
9. When the card goes to "Waits for you", read the PR, and then push "Approve".

**Expected board.** The card goes to "Blocked" in a few seconds. "Continue" moves it to "In progress". "Start again"
also moves it to "In progress". Last, the card goes to "Done".

**Other results.**

- The card goes to "In review" before your click: push "Block" there.
- The card goes to "Done" before your click: the issue is complete, and you cannot block it.
- You pushed "Terminal": type `/factory:continue` in the new window. Then push "Block" on the card.

### Lesson `approval`

**Purpose.** You make each merge wait for your approval.

The value `mode` in `.factory/factory.yaml` controls the merge. The value `risk` is the default. The value
`always-ask` always asks for your approval. A new issue uses the newest `.factory`, so you change the mode first.

**Steps.** Change the mode, and create the issue:

```
git pull
sed -i 's/mode: risk/mode: always-ask/' .factory/factory.yaml
git diff
git commit -am "Ask for an approval of each merge"
git push
tutorial/create-issues.sh approval
```

1. Make sure that the output of `git diff` shows only the line of the mode.
2. Wait two minutes. Make sure that the top of the board shows no warning.
3. Add the label `factory` to the issue.
4. Wait until the card is in "Waits for you".
5. Open the PR on GitHub, and read it.
6. Push "Approve" on the card.
7. Wait until the card is in "Done".

Then restore the mode:

```
git pull
sed -i 's/mode: always-ask/mode: risk/' .factory/factory.yaml
git commit -am "Let the risk decide the merge"
git push
```

**Expected board.** The card waits in "Waits for you" after the reviews and the risk check. It goes to "Done" in a few
seconds after your approval.

**Other results.**

- GitHub refuses the push: your repository has a rule for its default branch. Make a PR with the change, and merge it.
- A review rejects the change: the card goes back to "In progress", and waits later.

### Lesson `factory-change`

**Purpose.** You change the factory with an issue. A PR that changes `.factory` always waits for your approval,
because it changes the rules of the factory.

**Steps.** Create the issue:

```
tutorial/create-issues.sh factory-change
```

1. Add the label `factory` to the issue.
2. Wait until the card is in "Waits for you".
3. Open the PR on GitHub, and read it. Make sure that it changes only the prompt of the review.
4. Push "Approve" on the card.
5. Wait until the card is in "Done".

**Expected board.** The card tells that the pull request changes the folder `.factory`. The risk does not decide this
merge. The next issue uses the new prompt.

**Other results.** A review rejects the change: the card goes back to "In progress", and waits later.

### Lesson `parallel`

**Purpose.** The factory works on two issues at the same time. The two issues change the same method, so a conflict
is possible. A conflict occurs when two changes do not agree.

**Steps.** Create the issues:

```
tutorial/create-issues.sh parallel
```

1. Add the label `factory` to the two issues. Do it without a pause.
2. Wait until no card is in "In progress" or "In review".
3. If a card is in "Waits for you", read its PR, and push "Approve".
4. Wait until a card is in "Done".
5. Do steps 2 to 4 again until the two cards are in "Done".

Then check the result:

```
git pull
./mvnw verify
m=com.meterian.checkout.CheckoutCommand
java -cp target/classes $m laptop:100.00:1
java -cp target/classes $m pen:2.00:10
java -cp target/classes $m book:20.00:10
```

The totals are 95.00, 19.00, and 194.00.

**Expected board.** The two cards are in "In progress" at the same time, because the factory has two free slots. A slot
is a place for one issue in work.

**Other results.**

- A conflict occurs: the second card goes back to "In progress", and the agent solves the conflict. The card can then
  wait for a second approval.
- No slot is free: one card stays in "Queued". A card in "Waits for you" also holds a slot.
````

- [ ] **Step 3: Check the lines**

```bash
awk 'length > 120' docs/tutorial.md | grep -v '^|'
awk '/^```/{c=!c;next} c && (length>70 || /^ /)' docs/tutorial.md
```

Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add docs/tutorial.md
git commit -m "Add the optional lessons to the tutorial"
```

---

### Task 5: The live runs and the STE check

A person does this task. The agent checks GitHub and the log after each step. The run needs a new repository from the template, because the first one is deleted.

- [ ] **Step 1:** Do the core again with the corrected text. Record each sentence that is not clear.
- [ ] **Step 2:** Do `block`. Record the time of each move, and the result of "Start again".
- [ ] **Step 3:** Do `approval`, and then restore the mode.
- [ ] **Step 4:** Do `factory-change`.
- [ ] **Step 5:** Do `parallel`, and check the three totals.
- [ ] **Step 6:** Do "Clean up", with the delete of the repository.
  Do this step only after Step 9, when all the screenshots are complete.
- [ ] **Step 7:** A reader who did not write the texts checks each text of section 6.5 of the spec against its rules.
- [ ] **Step 8:** Correct each text with a finding. Do a lesson again when its steps change. Commit the corrections.

- [ ] **Step 9: Record the screenshots of the board during Steps 1 to 5**

Save each file in `FACTORY/docs/images/tutorial`, as PNG, with one window size. Show no name of a person, no private
repository, and no token. Thus use a run repository with a neutral name, and read each image before the commit.

| File | Moment | Place in the tutorial |
|---|---|---|
| `empty-board.png` | After the first start of the worker | The end of "Set up the factory" |
| `in-progress.png` | The card of `first-fix` in "In progress" | "Give an issue to the factory" |
| `in-review.png` | The reviews with their colors | "Watch the board" |
| `done.png` | The card of `first-fix` in "Done" | "Watch the board" |
| `blocked.png` | The blocked card with "Continue" and "Start again" | Lesson `block` |
| `waits-for-approval.png` | The card with "Approve" | Lesson `approval` |
| `factory-change.png` | The reason of the wait | Lesson `factory-change` |
| `parallel.png` | Two cards in "In progress" | Lesson `parallel` |
| `live-conversation.png` | The green box (with Task 7) | Lesson `live-mode` |

- [ ] **Step 10:** Add each image with a text alternative, for example
  `![The board with one card in "In progress"](images/tutorial/in-progress.png)`. Commit.

Expected: each lesson gives its expected board, or a result in "Other results".

---

### Task 6: The publication

**Files:**
- Modify: `FACTORY/README.md`

- [ ] **Step 1: Check the links of the README.** The section "Tutorial" and the row in "Documents" are there since 2026-11-25.

- [ ] **Step 2: Add this sentence to the end of the section "Tutorial"**

```markdown
Optional lessons show more functions.
```

- [ ] **Step 3: Make sure that no secret is in the template**

```bash
git -C "$SAMPLE" log -p | grep -n -i -E 'token|password|secret|api.key'
```

Expected: no output, or lines that the owner accepts.

- [ ] **Step 4: Commit, and push after the agreement of the owner**

```bash
git add README.md
git commit -m "Tell about the lessons in the README"
```

- [ ] **Step 5: The owner makes the template public, and marks the PR of `rossumworks` as ready**

**Warning: a public repository can be copied at once.** Make the template public before the merge of the PR, so that the link in the tutorial works.

- [ ] **Step 6: Check the result without a login**

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://github.com/MeterianHQ/rossumworks-tutorial
curl -s -o /dev/null -w '%{http_code}\n' https://github.com/MeterianHQ/rossumworks/blob/main/docs/tutorial.md
```

Expected: `200` two times, after the merge.

- [ ] **Step 7: The owner decides about issue #22.** Its live test is the trial of `factory-change`.

---

### Task 7: The lesson `live-mode`

**Condition:** `gh issue view 48 --repo MeterianHQ/rossumworks --json state` shows `CLOSED`, and a release has the fix.

**Files:**
- Create: `SAMPLE/tutorial/issues/live-mode/discount-a-loyal-customer.md`
- Modify: `SAMPLE/tutorial/test/test-issue-files.sh`
- Modify: `FACTORY/docs/tutorial.md`

- [ ] **Step 1: Add `live-mode` to `LESSONS` in the test, and see the test fail**

```bash
readonly LESSONS=(first-fix block approval factory-change parallel live-mode)
```

Run `tutorial/test/test-issue-files.sh`. Expected: `FAIL: the lesson live-mode has no issue`.

- [ ] **Step 2: Write the issue.** The percent is missing on purpose.

```markdown
Add a discount for a loyal customer

The program must accept the option `--loyal`. With this option, the customer gets the loyalty discount.

- The loyalty discount is a percent of the subtotal.
- Apply it after the discount of `--discount`.

Use the percent that the shop owner decided. Add a test for the new option.
```

- [ ] **Step 3: Run `tutorial/test/test-issue-files.sh`.** Expected: `ok`. Commit.

- [ ] **Step 4: Add this row to the table of the lessons, as the last row**

```markdown
| [`live-mode`](#lesson-live-mode) | Talk to the agent of an issue |
```

- [ ] **Step 5: Insert this text before "## Problems"**

````markdown
### Lesson `live-mode`

**Purpose.** You talk to the agent of an issue. This lesson needs the subscription.

The issue does not give the percent of the discount. Thus the agent usually asks a question, and the factory opens a
live conversation. [Live mode](live-mode.md) gives the details.

**Warning: end the conversation with `/factory:continue`. Do not type `/exit`: it ends the session, and the issue does
not continue.**

**Steps.** Create the issue:

```
tutorial/create-issues.sh live-mode
```

1. Add the label `factory` to the issue.
2. Watch the card. If it goes to "Waits for you" with a question, go to step 4.
3. If the agent asks no question, push "Terminal" while the card is in "In progress".
4. Open the conversation with "Terminal" or "Browser" on the card, if it is not open.
5. Tell the agent that the percent is 5.
6. Type `/factory:continue`.
7. When the card goes to "Waits for you" again, read the PR, and then push "Approve".

**Expected board.** The card is in "Waits for you" during the conversation. After `/factory:continue`, it goes back to
"In progress", and then to "In review".

**Other results.**

- The agent asks no question and uses its own percent: open the conversation as in step 3, and correct the percent.
- "Terminal" in the first seconds opens no conversation: push it again when the card shows the button.
- A card in "In review" also accepts "Terminal".
````

- [ ] **Step 6:** A person does the lesson, with the question and also with "Terminal". Correct the text and commit.
