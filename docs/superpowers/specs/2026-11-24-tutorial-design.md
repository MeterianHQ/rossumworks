# Tutorial design

## 1. Purpose

A new user tries RossumWorks on a sample project. The audience is the public. The tutorial has a short core
and independent optional lessons. The user can stop after any lesson.

The factory code does not change. The publication needs a public `MeterianHQ/rossumworks` with public releases.

## 2. Decisions

| Subject | Decision | Reason |
|---|---|---|
| Source of the sample | A GitHub template repository | The factory needs a repository where the user can push, merge, and assign. A fork has its issues off by default. |
| `.factory` in the sample | Not included | The user does the real setup with `create`, and no copy becomes old. |
| Issues | A script in the sample | A template does not copy issues. |
| Domain | A shop checkout | One domain gives a natural issue for each lesson. |
| Language | Java 21 with `mvnw` | The default build and the default allowed tools work without a change. |

## 3. Deliverables

| Deliverable | Content |
|---|---|
| Template repository `MeterianHQ/rossumworks-tutorial` | The checkout program, `CLAUDE.md`, `README.md`, the license, and the folder `tutorial`. |
| `docs/tutorial.md` in `rossumworks` | The full text of the tutorial. |
| `README.md` in `rossumworks` | A short section "Tutorial" after "Build", with a link to `docs/tutorial.md`. |

The README of the sample has a short introduction and a link to `docs/tutorial.md`. It does not repeat the steps.

## 4. The sample project

The program reads a small cart from its arguments and prints the total. JUnit is its only dependency, for the
tests. It has no server, no database, and no real payment.

| Class | Responsibility |
|---|---|
| `CartLine` | Holds one product, its unit price, and its quantity. |
| `Cart` | Holds the cart lines. |
| `Discount` | Gives the discount of an amount. |
| `PriceCalculator` | Calculates the total of a cart, with the discounts. |
| `PaymentRule` | Decides if a payment is accepted. |
| `CheckoutCommand` | Reads the arguments and prints the result. |

- `PriceCalculator` has one planned rounding bug. The tests pass at the start, because they do not cover it.
- `CLAUDE.md` gives the build command and a few code rules.
- `./mvnw verify` takes less than 30 seconds after the first download of the dependencies.

## 5. The issue script

The command is `tutorial/create-issues.sh <lesson>`. The lessons are `first-fix`, `live-mode`, `approval`,
`factory-change`, and `parallel`.

Each file `tutorial/issues/<lesson>/<name>.md` is one issue. The first line is the title. An empty line
follows. The rest is the body.

1. The script checks that `gh` and `git` are on the `PATH` and that `gh` has a login.
2. The script reads the GitHub repository from `origin`. It stops when this is
   `MeterianHQ/rossumworks-tutorial`.
3. The script reads the open and the closed issues. It omits a file when an issue has exactly the same title.
4. The script shows the repository and the titles of the new issues, and asks for a confirmation. Only `y`
   creates the issues. When there is no new issue, the script tells this and ends with success.
5. The script creates the issues without a label, and shows their links.

An unknown lesson or no lesson shows the list of the lessons, and the exit code is 1. The script stops at the
first failure of `gh`. A second run then creates only the issues that are not there.

The user adds the label `factory` by hand. Thus no issue uses the subscription by accident. The worker creates
the label at its first poll.

## 6. The tutorial text

### 6.1 Core: `first-fix`

1. Check the requirements: Linux, a JDK 21 or later, `claude` with a subscription, `gh` with a login, `git`,
   `setsid`, `kill`, `tmux`, `jq`, and `curl`. `ANTHROPIC_API_KEY` is not set.
2. Select "Use this template" to make a repository. A private repository is the safe default. Clone it.
3. Download the jar from the releases. Run `java -jar <path of the jar> create` in the clone.
4. Merge the PR `factory/setup`, and pull the default branch into the clone.
5. Run `.factory/run.sh`. Open the board on the address that `create` used.
6. In a second terminal, run `tutorial/create-issues.sh first-fix`.
7. Add the label `factory` to the issue.
8. Watch the card to `done`. Read the PR and the reviews.

The risk check decides the merge. When the card goes to `waits-for-approval`, the user adds
`factory:approve`. The text shows both paths.

### 6.2 Optional lessons

Each lesson needs only the core. Each one gives its purpose, its steps, the expected board, and the other
possible results.

| Lesson | Issue | Steps of the user | Result |
|---|---|---|---|
| `live-mode` | A discount rule that is not clear | Answer the question of the agent. When the agent asks no question, push Enter while the action `coding` runs. End with `/factory:continue`. | The conversation opens on each run when the user pushes Enter. |
| `approval` | A change of the payment rule | Before the issue, set `mode: always-ask` in `.factory/factory.yaml`, and push the commit to the default branch. Add `factory:approve` when the card waits. | The merge always waits for the approval, after the reviews pass. |
| `factory-change` | A change of the prompt `review-quality.md` in `.factory` | Read the PR. Add `factory:approve` when the card waits. | The merge always waits for the approval, after the reviews pass. |
| `parallel` | Two discounts that change the same method of `PriceCalculator` | Add the label `factory` to the two issues together. | Two cards are in work when two slots are free. A conflict is possible. The text does not promise it. |

A new issue uses the newest version of `.factory`. Thus the user pushes the mode of `approval` before the user
adds the label. The last step of `approval` sets `mode: risk` again.

### 6.3 Problems and clean-up

"Problems" gives a solution for each of these: `ANTHROPIC_API_KEY` is set, a tool is missing, the assignment
fails, the usage limit stops the work, and a card goes to `blocked`.

"Clean up" has this order: stop the worker with Ctrl-C, delete the repository on GitHub, delete the clone, and
delete the work folder `~/.rossumworks/<owner>-<name>`. The text tells the user to check the name before each
delete.

### 6.4 Safety

The start of the tutorial tells the user that the agents run commands on their machine. It also tells that each
issue uses the subscription: a coding session, four reviews, and a risk session, and more after a reject.

## 7. Test

1. `./mvnw verify` passes on the sample, and `shellcheck` gives no finding.
2. A test with a fake `gh` checks the script: a first run, a second run, a refused confirmation, the template
   repository as `origin`, an unknown lesson, and a failure in the middle.
3. A test that the fix of `first-fix` needs fails on the sample. This test is not in the sample.
4. A person follows the full tutorial in a new repository from the template. Each lesson runs at least once.
5. The run of the core gives its time and its subscription use. The tutorial shows these measured values.

## 8. Dependencies

- `rossumworks` and its releases are public before the publication.
- Issue #48 (an ended live session keeps its slot) is fixed before the publication of `live-mode`.
- The run of `factory-change` also gives the live test of issue #22.
- The tutorial names Linux as a requirement until issue #55 (macOS) is done.

## 9. Order of work

1. The sample, the script, and the core, with one full run.
2. The optional lessons, one at a time.
3. The publication: the template repository becomes public, and the README gets its section "Tutorial".
