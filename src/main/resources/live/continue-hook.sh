#!/bin/bash
# Hook UserPromptSubmit. The first argument is the path of the marker file.
# A prompt that starts with /factory:continue writes the marker and blocks the prompt.
set -u

marker_path="${1:-}"
[ -n "$marker_path" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0

prompt="$(jq -r '.prompt // ""' 2>/dev/null)" || exit 0

case "$prompt" in
    [[:space:]]*) prompt="${prompt#"${prompt%%[![:space:]]*}"}" ;;
esac

case "$prompt" in
    /factory:continue | /factory:continue[[:space:]]*)
        date > "$marker_path" || exit 0
        echo "The factory takes the issue back. You can close this conversation." >&2
        exit 2
        ;;
esac
exit 0
