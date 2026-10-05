#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

releaseVersion="${1:-}"
if [[ "$#" != 1 ]]; then
  echo "Usage: bash shell/version.sh <major.minor.patch|4.0 patch number>" >&2
  exit 1
fi
if [[ "$releaseVersion" =~ ^(0|[1-9][0-9]*)$ ]]; then
  releaseVersion="4.0.${releaseVersion}"
fi
if [[ ! "$releaseVersion" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "A release version such as 4.0.5 is required (no SNAPSHOT)." >&2
  exit 1
fi
if git rev-parse -q --verify "refs/tags/v${releaseVersion}" >/dev/null; then
  echo "Release tag v${releaseVersion} already exists; choose a new version." >&2
  exit 1
fi

./mvnw -B -ntp versions:set -DnewVersion="$releaseVersion" \
  -DprocessAllModules=true -DgenerateBackupPoms=false
./mvnw -B -ntp clean verify
printf 'Prepared %s. Review and commit the POM changes, then publish tag v%s.\n' \
  "$releaseVersion" "$releaseVersion"
