#!/bin/bash
# Test double of the interactive claude command in live mode. See the FAKE_CLAUDE_* variables.
printf '%s\n' "$@" > "$FAKE_CLAUDE_ARGUMENTS"
env > "$FAKE_CLAUDE_ENVIRONMENT"
pwd > "$FAKE_CLAUDE_DIRECTORY"
if [ "$FAKE_CLAUDE_LIVE_EXIT" = 1 ]; then
  sleep 1
  exit 0
fi
if [ "$FAKE_CLAUDE_LIVE_OLD_LINK" = 1 ]; then
  echo "Earlier in the conversation: https://claude.ai/code/session_OLD"
fi
if [ "$FAKE_CLAUDE_LIVE_OLD_TRUST" = 1 ]; then
  echo "Earlier in the conversation: Yes, I trust this folder"
  for line in 1 2 3 4 5 6; do
    echo "Conversation line $line"
  done
else
  echo "Quick safety check: Is this a project you trust? > 1. No, exit  2. Yes, I trust this folder"
fi
if [ -n "$FAKE_CLAUDE_LIVE_DEAF_SECONDS" ]; then
  # The real claude shows the trust prompt before it reads the keys: it loses the keys of this time.
  sleep "$FAKE_CLAUDE_LIVE_DEAF_SECONDS"
  # bash 3.2 of macOS accepts only a whole number of seconds here.
  while read -r -s -t 1 lost_key; do :; done
fi
read -r answer
echo answered > "$FAKE_CLAUDE_ANSWER"
if [ "$FAKE_CLAUDE_LIVE_NO_LINK" != 1 ]; then
  echo "/remote-control is active · Continue here, on your phone, or at https://claude.ai/code/session_FAKE123"
fi
sleep 60
