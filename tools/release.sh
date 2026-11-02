#!/usr/bin/env bash
# Cut a release: tools/release.sh 1.2.0
# Sets nimbus.version, turns the changelog's "Unreleased" section into the release, commits, tags vX.Y.Z
# and pushes. The Release workflow on GitHub then builds, signs and publishes the APK with those notes.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:?usage: tools/release.sh X.Y.Z}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must look like 1.2.0" >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Commit or stash your changes first." >&2; exit 1; }
[[ "$(git rev-parse --abbrev-ref HEAD)" == main ]] || { echo "Release from main." >&2; exit 1; }
git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && { echo "v$VERSION already exists." >&2; exit 1; }
grep -q '^## \[Unreleased\]' CHANGELOG.md || { echo "CHANGELOG.md needs an [Unreleased] section." >&2; exit 1; }

python3 -I - "$VERSION" <<'PY'
import datetime, re, sys
version = sys.argv[1]
props = open("gradle.properties").read()
props = re.sub(r"(?m)^nimbus\.version=.*$", f"nimbus.version={version}", props)
open("gradle.properties", "w").write(props)
log = open("CHANGELOG.md").read()
today = datetime.date.today().isoformat()
body = log.split("## [Unreleased]", 1)[1].split("\n## [", 1)[0].strip()
if not body:
    sys.exit("The [Unreleased] section of CHANGELOG.md is empty: describe the release first.")
log = log.replace("## [Unreleased]", f"## [Unreleased]\n\n## [{version}] - {today}", 1)
open("CHANGELOG.md", "w").write(log)
PY

git commit -qam "Release $VERSION"
git tag -a "v$VERSION" -m "Nimbus $VERSION"
git push origin main "v$VERSION"
echo "Pushed v$VERSION: the Release workflow builds and publishes it."
