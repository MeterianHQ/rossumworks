#!/bin/bash
# Test double of the interactive claude command in live mode. See the FAKE_CLAUDE_* variables.
printf '%s\n' "$@" > "$FAKE_CLAUDE_ARGUMENTS"
env > "$FAKE_CLAUDE_ENVIRONMENT"
pwd > "$FAKE_CLAUDE_DIRECTORY"
if [ "$FAKE_CLAUDE_LIVE_EXIT" = 1 ]; then
  exit 0
fi
echo "Quick safety check: Is this a project you trust? > 1. No, exit  2. Yes, I trust this folder"
read -r answer
if [ "$FAKE_CLAUDE_LIVE_NO_LINK" != 1 ]; then
  echo "/remote-control is active · Continue here, on your phone, or at https://claude.ai/code/session_FAKE123"
fi
sleep 60
