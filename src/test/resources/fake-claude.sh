#!/bin/bash
# Test double of the claude command. See the FAKE_CLAUDE_* variables.
printf '%s\n' "$@" > "$FAKE_CLAUDE_ARGUMENTS"
env > "$FAKE_CLAUDE_ENVIRONMENT"
if [ "$FAKE_CLAUDE_BACKGROUND_CHILD" = 1 ]; then
  sleep 30 &
  echo $! > "$FAKE_CLAUDE_CHILD_PID_FILE"
fi
first_line=true
while IFS= read -r line; do
  printf '%s\n' "$line"
  if [ "$first_line" = true ] && [ -n "$FAKE_CLAUDE_SLEEP" ]; then
    sleep "$FAKE_CLAUDE_SLEEP"
  fi
  first_line=false
done < "$FAKE_CLAUDE_STREAM"
exit "${FAKE_CLAUDE_EXIT:-0}"
