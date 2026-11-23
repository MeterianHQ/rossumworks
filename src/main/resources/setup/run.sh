#!/usr/bin/env bash
# Starts the worker of the factory with the latest release of rossumworks.
# The arguments go to the run command, for example: .factory/run.sh --config <file>
set -euo pipefail

readonly RELEASES_PAGE="https://github.com/MeterianHQ/rossumworks/releases"
readonly LATEST_RELEASE_API="https://api.github.com/repos/MeterianHQ/rossumworks/releases/latest"
readonly CACHE_DIRECTORY="${HOME}/.rossumworks/jars"

# The worker uses the same token: FACTORY_GITHUB_TOKEN, else the token of gh.
read_token() {
    if [[ -n "${FACTORY_GITHUB_TOKEN:-}" ]]; then
        echo "${FACTORY_GITHUB_TOKEN}"
    elif command -v gh > /dev/null; then
        gh auth token 2> /dev/null || true
    fi
}

call_github() {
    local accept="$1"
    local url="$2"
    shift 2
    local authorization=()
    if [[ -n "${token}" ]]; then
        authorization=(--header "Authorization: Bearer ${token}")
    fi
    curl --silent --show-error --fail --location --header "Accept: ${accept}" "${authorization[@]}" "$@" "${url}"
}

# Prints the name of the jar and the API URL of the jar, on one line.
find_latest_jar() {
    call_github "application/vnd.github+json" "${LATEST_RELEASE_API}" \
        | jq --raw-output --exit-status \
            '.assets[] | select(.name | test("^rossumworks-[0-9.]+\\.jar$")) | "\(.name) \(.url)"' \
        | head -n 1 | grep .
}

download_latest_jar() {
    local name url
    read -r name url < <(find_latest_jar) || return 1
    local jar="${CACHE_DIRECTORY}/${name}"
    if [[ ! -f "${jar}" ]]; then
        echo "Download of ${name}" >&2
        call_github "application/octet-stream" "${url}" --output "${jar}.partial" || {
            rm -f "${jar}.partial"
            return 1
        }
        mv "${jar}.partial" "${jar}"
    fi
    echo "${jar}"
}

find_newest_cached_jar() {
    find "${CACHE_DIRECTORY}" -maxdepth 1 -name 'rossumworks-*.jar' -printf '%f\n' \
        | sort --version-sort | tail -n 1 | sed "s#^#${CACHE_DIRECTORY}/#" | grep .
}

select_jar() {
    if download_latest_jar; then
        return 0
    fi
    echo "Cannot download the latest release. The script uses the newest jar in ${CACHE_DIRECTORY}." >&2
    find_newest_cached_jar || {
        echo "The folder ${CACHE_DIRECTORY} has no jar. Download a jar from ${RELEASES_PAGE}." >&2
        return 1
    }
}

mkdir -p "${CACHE_DIRECTORY}"
token=$(read_token)
jar=$(select_jar)
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec java -jar "${jar}" run "$@"
