# A worker on a cloud VM

This page tells how you run the worker on a Linux VM in the cloud. The VM has no desktop. You use the board and
the live mode from your own computer, through an SSH tunnel.

Read [Getting started](getting-started.md) first. This page gives only the steps that are different on a VM.

## Select the VM

- Use Linux on x64 or on arm64, with glibc 2.34 or later, for example Ubuntu 22.04, Debian 12, or RHEL 9.
  Then the native program runs, and the VM needs no JDK.
- Give the VM sufficient CPU, memory, and disk for your build command. Each slot runs one build at a time. See
  [Configuration](configuration.md).
- Open only the SSH port in the firewall of the VM. Do not open the port of the board.

## Prepare the VM

Do these steps as the user that runs the worker. Do not use `root`.

1. Install `git`, `tmux`, `jq`, `curl`, and `gh`. On Ubuntu and Debian, `setsid` and `kill` are in
   `util-linux` and `procps`, and the system usually has them.
2. Install `claude`. See the documents of Claude Code.
3. Install the tools of your build command, for example a JDK, Maven, or Node.js.
4. Run `claude` once in the terminal, and answer its first questions.

## Log in without a desktop

The VM has no browser. The two logins give a link and a code. You open the link in the browser of your own
computer.

1. Log in to GitHub with `gh auth login`. Select "Login with a web browser". Open the link on your computer,
   and type the code that `gh` shows.
2. For the subscription, log in with `claude`. Open the link that `claude` shows on your computer. Then copy the
   code from the browser into the terminal of the VM.
3. For an API key, do not log in with `claude`. Put the key in a file that only you can read. See "Start the
   worker as a service" below.

See [Providers](providers.md). Read [Legal considerations](legal-considerations.md) before you select a
provider.

## Install the factory

1. Clone your repository on the VM.
2. Go to the folder of the clone.
3. Download the native program of the VM, and check it. See
   [The programs of a release](getting-started.md#the-programs-of-a-release).
4. Run `./<program> create --web 127.0.0.1:8090`.
5. Give a password for the board. Use a password also on a loopback address, because the SSH tunnel gives
   access to the board.
6. At the question about the terminal command, push Enter. Without `gnome-terminal`, the default is no command.
7. Merge the pull request `factory/setup`, and pull the default branch.

Keep the board on `127.0.0.1`. The board uses plain HTTP, so do not put it on `0.0.0.0` on a VM in the cloud.

## Start the worker as a service

A worker in a terminal stops when you log out. Thus start the worker with `systemd`. The worker stops its
`claude` sessions on `systemctl stop`, the same as with Ctrl-C.

1. Write the file `/etc/systemd/system/rossumworks.service`. Replace `factory` with your user, and
   `/home/factory/myrepo` with the folder of your clone.

   ```ini
   [Unit]
   Description=RossumWorks worker
   After=network-online.target
   Wants=network-online.target

   [Service]
   User=factory
   WorkingDirectory=/home/factory/myrepo
   Environment=PATH=/home/factory/.local/bin:/usr/local/bin:/usr/bin:/bin
   EnvironmentFile=-/home/factory/.config/rossumworks/environment
   ExecStart=/home/factory/myrepo/.factory/run.sh
   Restart=on-failure
   RestartSec=60

   [Install]
   WantedBy=multi-user.target
   ```

2. Make sure that `PATH` has the folders of `claude`, `gh`, and the tools of your build command.
3. For an API key, write `ANTHROPIC_API_KEY=<key>` in `/home/factory/.config/rossumworks/environment`.
4. For a GitHub token in place of the `gh` login, write `FACTORY_GITHUB_TOKEN=<token>` in the same file.
5. Run `chmod 600 /home/factory/.config/rossumworks/environment`.
6. Run `sudo systemctl daemon-reload`.
7. Run `sudo systemctl enable --now rossumworks`.
8. Run `journalctl -u rossumworks -f` to read the log of the worker.

The exit code 1 and the exit code 2 tell you to change the configuration. See
[Getting started](getting-started.md#the-exit-codes). `Restart=on-failure` then starts the worker again
after 60 seconds, so read the log and correct the problem.

`run.sh` downloads the latest release at each start. Thus `sudo systemctl restart rossumworks` also updates
the factory.

## Open the board

1. On your computer, run `ssh -N -L 8090:127.0.0.1:8090 factory@<address of the VM>`.
2. Open `http://127.0.0.1:8090/` in your browser.
3. Type the password of the board.

The tunnel encrypts the traffic between your computer and the VM. Only a person with SSH access to the VM can
open the board.

## Use the live mode

Use the button Browser, or the label `factory:enter`. The conversation opens on claude.ai, also on a phone. See
[Live mode](live-mode.md).

Do not use the button Terminal. The tunnel makes your browser look local, so the board shows Terminal. But the
VM has no desktop, so no window opens. The worker then writes a note in the status comment, and the Remote
Control link still works.

### Talk to the agent over SSH

The worker starts each live conversation in its own tmux server, with the socket name `rossumworks`. The
session of issue `<n>` has the name `factory-issue-<n>`. You can attach to this session over SSH.

1. Open the live conversation with the button Browser or the label `factory:enter`.
2. Log in to the VM with SSH, as the user of the service.
3. Run `tmux -L rossumworks attach -t factory-issue-<n>`.
4. To detach, type `Ctrl-b d`. Do not type `/exit`, because it ends the session.
5. To give the issue back to the factory, type `/factory:continue`.

The session exists only while the live conversation is open. To see the open sessions, run
`tmux -L rossumworks ls`. Do not set `PrivateTmp=` in the unit. tmux keeps its socket in `/tmp`, and a
private `/tmp` hides the socket from your SSH login.

### Keep one SSH session open for all the conversations

You can attach once, and then go to each live conversation when it opens. For this, you make a session of your
own on the tmux server of the factory. The worker closes only the sessions with the name `factory-issue-<n>`,
so it does not close your session.

1. Log in to the VM with SSH, as the user of the service.
2. Run `tmux -L rossumworks new-session -A -s console`.
3. Run `tmux set-option -g detach-on-destroy off`. Then the end of a live conversation sends you back to
   `console`, and does not detach you.
4. When a live conversation opens, type `Ctrl-b s`, and select `factory-issue-<n>`.
5. To go back to `console`, type `Ctrl-b s` again, and select `console`.

The session `console` stays after you log out or lose the network. To attach again, run the command of step
2. A restart of the VM removes the session, so do the steps again after a restart.

The shell of `console` gets the environment of the tmux server of the factory. Thus it can contain
`FACTORY_GITHUB_TOKEN`, when the environment file of the service sets it. The worker removes
`ANTHROPIC_API_KEY` from this environment.

## Limits

- The live mode needs the subscription. With an API key, the factory blocks an issue that needs you, and you
  answer on GitHub.
- A restart of the worker asks for the password of the board again.
