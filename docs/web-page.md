# Web page

The worker can show a web page, the **board**. It shows the issues of the factory as cards, and its buttons let
you control the work. This page tells how you set up the board, what it shows, and what its buttons do.

Without a `web` section in `worker.yaml`, the worker shows no board.

## Set up the board

The command `create` writes the `web` section for you. It asks for the address and for a password. See
[Getting started](getting-started.md).

To set the password yourself:

1. Make the hash of a password: `<path of the program> hash-password`. With the jar, the program is
   `java -jar rossumworks-$VERSION.jar`. The command asks for the
   password two times. The password must have 12 characters or more.
2. Copy the output line `password-hash: pbkdf2-sha256:...` into the `web` section of `worker.yaml`:

   ```yaml
   web:
     address: 127.0.0.1    # optional; 0.0.0.0 opens the board to the LAN
     port: 8090            # optional
     password-hash: "pbkdf2-sha256:600000:..."   # optional; without it, the board has no login
   ```

3. Start the worker. It writes the address of the board, for example
   `The web page is at http://127.0.0.1:8090/`. An address that is not a loopback address also gives a warning
   about plain HTTP.

`worker.yaml` holds only the hash, never the password. When the port is in use, the worker stops with the exit
code 1.

### No password

To get a board without a password, push Enter at the password question of `create`. `create` then writes the
`web` section without `password-hash`. To remove the password later, remove the line `password-hash`.

At start, the worker then writes `The web page has no password.` On an address that is not a loopback address,
it also writes a warning: every device on the LAN can use the board.

> **Warning.** The board can approve a merge, block an issue, and open a live conversation. Without a password,
> every program and every person that can reach the address can do these actions. Use no password only on a
> loopback address, for example `127.0.0.1`. The board refuses a click (a POST) that a page of another site
> sends, also without a password.

Without a terminal, `create` needs the password in `FACTORY_WEB_PASSWORD`, or `--no-web`.

### Login

With a password, the board asks for it once on each device. A correct password gives a session cookie for 30
days. The board, its cards, and its updates all need the login.

After 5 wrong passwords, the worker refuses all logins for 15 minutes, also with the correct password.

## What the board shows

### The header

The header shows the health of the worker:

- The time when the worker started, and the time of its last poll.
- The number of slots in use, and the number of all the slots. See [Configuration](configuration.md).
- The isolation mode: how the worker isolates the agent. See [Configuration](configuration.md).
- With the subscription, each usage window with its percent. The tooltip tells the time of the value.
  A phone shows no tooltip.
- With an API key, the cost of today against the daily budget. See [Providers](providers.md).

When `.factory` of your default branch does not load, a warning line at the top of the board gives the reason.

### The columns

The board has the column Queued, and then the columns of the workflow, in the order of their first use in
`.factory/factory.yaml`. For the default workflow, the columns are Queued, In progress, In review, Waits for
you, Blocked, and Done.

The board shows the columns of the newest version of `.factory` that loads. A card whose column is not in that
version goes to an extra column at the end. The column of the
state with the action `finish` keeps the 20 newest cards. In the default workflow, it is Done.

On a narrow screen, the columns go one under the other, and "Waits for you" is at the top.

### The cards

A card shows:

- The issue and its state, and a link to the pull request.
- The result of each review. At the start of a review round, all the results go gray. Each result gets its
  color when its review ends.
- How many times the issue entered each state with a limit, and the limit. For example, "implement 1/3".
- The result of the risk check, in one line. The reasons are in the status comment.
- How much of its context window the agent uses.
- The newest note of the status comment.
- For a card in Done, the date when the work ended.

A card in a state with the action `approval` or `conversation` waits for you. See
[Factory definition](factory-definition.md). It shows the reason and the
Remote Control link.

The board changes when the state of an issue changes. You do not need to load the page again. The board has a
dark theme.

## The buttons

A button shows only when its action is possible. When you click, the worker checks the rule again.

| Button | Label | What it does |
|---|---|---|
| Terminal, Browser | `factory:enter` | Opens the live mode in the place that you choose. See [Live mode](live-mode.md). |
| Approve | `factory:approve` | Shows in a state with the action `approval`. It approves the merge, or accepts a new text of the issue. |
| Block | `factory:block` | Stops the running action, and moves the issue to the [block state](glossary.md). It asks for a confirmation first. It does not show in a state without an action, or in a state with the action `finish`. |
| Start again | `factory:start-again` | Discards the earlier work of a blocked issue, and starts the issue again. It asks for a confirmation first. See [Issues and labels](issues-and-labels.md). |
| Continue | None | Ends the live mode, as `/factory:continue` does. In a state without an action, for example `blocked`, it sends the event `continue`. |

Start again shows only on a blocked issue without a live conversation.

The buttons add their labels with the token of the worker. A button that adds a label makes one GitHub request.
Continue adds no label: it writes the local marker of the live mode. The board makes no other GitHub request. It
shows the state from the last poll of the worker.

### After a click

- The button shows a turning wheel, and you cannot click it again. When your browser asks for less motion,
  the button shows "…".
- The card shows a blue line, for example "Approval sent. The worker takes it in a few seconds." This line
  replaces the reason of the wait. It goes away when the status comment shows the effect of the request. For
  example, the line of Block stays until the card moves to the column of the block state.
- Until the worker acts on the request, the card is gray, and all its buttons are disabled.

The same blue line shows for `/factory:continue` in a live conversation. It also shows for a label that you
add on GitHub, when the worker accepts the label at a poll.

After each click, the board asks the worker for an early poll. The worker polls 2 seconds later, and does not
wait for the poll interval. Thus a button has its effect in a few seconds. The 2 seconds give GitHub the time to
show the new label in the list of issues. If GitHub does not show the label yet, the worker acts at the next
usual poll.

A label that the board did not add hides a button only after the worker finds the label at a poll. A button of
an old page gets "This action is not possible now." when the card changed. Load the page again.

### Terminal and Browser

The buttons Terminal and Browser are side by side in one row. Each one has a tooltip. A phone shows no tooltip, so
the names are clear without it. A card is narrow, so the buttons have no icons. Terminal shows only in a browser on
the machine of the worker. Terminal and Browser are on a row below Approve and Block, so that Approve and Block keep
their place when the Enter buttons come.

After a click on one of them, the card shows only the blue line of the chosen place:

- For Terminal, the line says that a terminal window opens on this machine.
- For Browser, the line says that the new tab goes to the conversation.
- For a label that you add on GitHub, the line says that the link goes to a comment of the issue.

While the conversation is open, the card shows a green box "Live conversation open". The box names the chosen
place. It also has the buttons Terminal and Browser, and the chosen one is blue. Each button opens the
conversation again in its place. For example, Terminal opens a new window after you close the first one.
Terminal in this box makes no GitHub request. Browser opens the Remote Control link in a new tab.

## Stop and start the factory

The top right corner of the board shows the state of the factory:

| State | Color | Meaning |
|---|---|---|
| Running | Green | The worker starts and moves the issues. |
| Stopping | Yellow | You clicked **Stop**. At least one issue is still in work. |
| Stopped | Red | You clicked **Stop**, and no issue is in work. |

**Stop** interrupts no action. Each issue in work does these steps:

1. The current action runs to its end, for example the agent session or the build.
2. The worker writes the status comment, so the issue moves to its next state.
3. The work on the issue stops. The card stays in its column.

When the last issue stops, the state changes from Stopping to Stopped. **Start** turns the factory on again.
The worker then continues the stopped issues, and starts the waiting issues, in about 2 seconds.

While the factory is stopping or stopped:

- No issue starts or continues. This includes the stopped issues and Start again.
- The worker does not touch a new issue with the label `factory` and no assignee. It does not assign the issue, writes no
  status comment, and shows no card. Thus another factory can take the issue. The worker takes it at the first
  poll after **Start**, if no other factory took it first.
- Block works at once. Enter opens the live conversation.
- An Approve and a `/factory:continue` wait until you click **Start**.

A stop stays after a restart of the worker. The marker file is `<work folder>/factory-stopped`. The marker
`pick-up-paused` of an earlier version of the factory also keeps the factory stopped. **Start** removes the two
files.

## Known limits

- The board uses plain HTTP. On the LAN, another device can read the password and the cookie.
- A restart of the worker asks for the password again. It also removes the cards in Done.
- The lock after 5 wrong passwords is for the whole worker, not for one device.
- If GitHub does not show a new label at the early poll, a button has its effect after one poll interval at most.
- A browser opens about 6 connections to one address. With many tabs of the board in one browser, the board can
  stop its updates, and the page and the buttons can fail.
