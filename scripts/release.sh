#!/usr/bin/env bash
#
# Cuts a release of GraphKt to Maven Central: validates, uploads, and publishes.
#
# Usage:
#   scripts/release.sh [--scope patch|minor|major] [--yes] [--dry-run] [--skip-check]
#
# Run it from a release branch (`3.0.x`, `3.1.x`, ...). Nebula derives the version from the
# latest tag: a patch bump by default, or the bump that --scope names.
#
# The steps, in order:
#   1. preflight   — clean tree, branch in sync with origin, credentials present
#   2. version     — asked of nebula rather than assumed
#   3. check       — a clean `check`, so the release is verified rather than hoped for
#   4. coordinates — publishes to a scratch Maven repository and refuses any artifact outside
#                    the `com.steamstreet.graphkt` group
#   5. final       — uploads every module, including the Gradle plugin and its marker, closes
#                    the staging repository, tags, pushes
#   6. validate    — waits for the deployment to reach VALIDATED
#   7. publish     — the irreversible step, confirmed unless --yes
#   8. verify      — waits for PUBLISHED and for the artifacts to answer on repo1
#
# `--dry-run` stops after step 4, before anything is uploaded or tagged.
#
# Two things this script exists to prevent, both of which have happened to awskt, which
# releases the same way:
#
#   `final` exits 0 without publishing anything. It closes the staging repository, and
#   the deployment then sits at VALIDATED until something explicitly publishes it. A
#   release driven by `final` alone looks successful and ships nothing.
#
#   A close that times out fails the build *after* the artifacts are uploaded, leaving
#   the release untagged and the next build reusing the version. The build sets a
#   30 minute clientTimeout for this reason.

set -euo pipefail

OSSRH_API="https://ossrh-staging-api.central.sonatype.com"
PORTAL_API="https://central.sonatype.com/api/v1/publisher"
REPO1="https://repo1.maven.org/maven2"
GROUP="com.steamstreet.graphkt"

SCOPE=""
ASSUME_YES=0
DRY_RUN=0
SKIP_CHECK=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scope)      SCOPE="${2:-}"; shift 2 ;;
    --scope=*)    SCOPE="${1#*=}"; shift ;;
    --yes|-y)     ASSUME_YES=1; shift ;;
    --dry-run)    DRY_RUN=1; shift ;;
    --skip-check) SKIP_CHECK=1; shift ;;
    -h|--help)    sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)            echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -n "$SCOPE" && ! "$SCOPE" =~ ^(patch|minor|major)$ ]]; then
  echo "--scope must be patch, minor or major" >&2
  exit 2
fi

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\n\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

confirm() {
  [[ $ASSUME_YES -eq 1 ]] && return 0
  local reply
  read -r -p "$1 [y/N] " reply </dev/tty
  [[ "$reply" == "y" || "$reply" == "Y" ]]
}

# --- 1. preflight ------------------------------------------------------------

say "Preflight"

[[ -f "$ROOT/gradlew" && -d "$ROOT/gradle-plugin" ]] || die "not in the GraphKt repository root"

if [[ -n "$(git status --porcelain)" ]]; then
  die "working tree is dirty; nebula will not release from it"
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$BRANCH" =~ ^[0-9]+\.[0-9]+\.x$ ]] || die "releases are cut from a release branch such as 3.0.x, not '$BRANCH'"

git fetch --quiet --tags origin "$BRANCH" || die "could not fetch origin/$BRANCH"

if [[ -n "$(git rev-list "origin/$BRANCH..HEAD")" ]]; then
  die "$BRANCH has unpushed commits; push them first so the tag refers to a commit on origin"
fi
if [[ -n "$(git rev-list "HEAD..origin/$BRANCH")" ]]; then
  die "$BRANCH is behind origin/$BRANCH; pull first"
fi

GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
read_prop() { grep -E "^$1=" "$GRADLE_PROPS" 2>/dev/null | head -1 | cut -d= -f2- || true; }

CENTRAL_USER="${MAVEN_CENTRAL_USERNAME:-$(read_prop mavenCentralUsername)}"
CENTRAL_PASS="${MAVEN_CENTRAL_PASSWORD:-$(read_prop mavenCentralPassword)}"

if [[ $DRY_RUN -eq 0 ]]; then
  [[ -n "$CENTRAL_USER" && -n "$CENTRAL_PASS" ]] || \
    die "mavenCentralUsername/mavenCentralPassword not found in $GRADLE_PROPS"
  [[ -n "$(read_prop signing.keyId)" ]] || \
    die "signing.keyId not found in $GRADLE_PROPS; Central rejects unsigned artifacts"
  info "credentials: present"
fi

TOKEN="$(printf '%s:%s' "$CENTRAL_USER" "$CENTRAL_PASS" | base64)"

info "branch:      $BRANCH (in sync with origin)"

api_get()  { curl -fsS -u "$CENTRAL_USER:$CENTRAL_PASS" -H "Accept: application/json" --max-time 120 "$@"; }
api_post() { curl -fsS -X POST -H "Authorization: Bearer $TOKEN" --max-time 300 "$@"; }

# --- 2. version --------------------------------------------------------------

say "Resolving version"

# Expanded below as ${SCOPE_ARG[@]+...}, never plainly: macOS ships bash 3.2, which under `set -u`
# treats an empty array's "${SCOPE_ARG[@]}" as an unbound variable and exits.
SCOPE_ARG=()
[[ -n "$SCOPE" ]] && SCOPE_ARG=(-Prelease.scope="$SCOPE")

VERSION="$(./gradlew properties -Prelease.stage=final ${SCOPE_ARG[@]+"${SCOPE_ARG[@]}"} --console=plain -q 2>/dev/null \
  | grep -E '^version:' | head -1 | awk '{print $2}')"

[[ -n "$VERSION" ]] || die "could not determine the version nebula would use; check --scope against the branch name"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
  die "resolved version '$VERSION' is not a release version; check --scope and the branch name"

git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && \
  die "tag v$VERSION already exists; this version has been released or at least tagged"

info "version: $VERSION"

# --- 3. check ----------------------------------------------------------------

if [[ $SKIP_CHECK -eq 1 ]]; then
  say "Skipping check (--skip-check)"
else
  say "Running clean check"
  info "every target that can build on this host is compiled and tested"
  ./gradlew clean check --console=plain || die "check failed; nothing has been uploaded"
fi

# --- 4. coordinates ----------------------------------------------------------

# Every coordinate, in the POMs and in the Gradle module metadata that points consumers at the
# per-target variants, has to sit under one group. awskt 3.0.0 shipped a split namespace because a
# rewritten artifactId was silently overwritten for the target publications, and Central coordinates
# cannot be withdrawn, so this is checked on the real publications before anything is uploaded.
say "Checking published coordinates"

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/graphkt-release.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

./gradlew publishToMavenLocal -Prelease.stage=final ${SCOPE_ARG[@]+"${SCOPE_ARG[@]}"} \
  -Dmaven.repo.local="$STAGE" --console=plain -q || die "publishing to a scratch repository failed"

STAGE="$STAGE" GROUP="$GROUP" VERSION="$VERSION" python3 - <<'EOF' || die "published coordinates are inconsistent; nothing has been uploaded"
import json, os, re, sys, pathlib

stage, group, version = pathlib.Path(os.environ["STAGE"]), os.environ["GROUP"], os.environ["VERSION"]
bad, artifacts = [], []

for pom in sorted(stage.rglob("*.pom")):
    text = pom.read_text()
    g = re.search(r"<project[^>]*>.*?<groupId>([^<]+)</groupId>", text, re.S).group(1)
    a = re.search(r"</groupId>\s*<artifactId>([^<]+)</artifactId>", text, re.S).group(1)
    v = re.search(r"</artifactId>\s*<version>([^<]+)</version>", text, re.S).group(1)
    artifacts.append(f"{g}:{a}")
    if g != group:
        bad.append(f"{pom.name}: group {g}")
    if v != version:
        bad.append(f"{pom.name}: version {v}")
    for dep_g, dep_a in re.findall(r"<dependency>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>", text):
        if dep_g.startswith("com.steamstreet") and dep_g != group:
            bad.append(f"{pom.name}: depends on {dep_g}:{dep_a}")

for module in sorted(stage.rglob("*.module")):
    data = json.loads(module.read_text())
    if data["component"]["group"] != group:
        bad.append(f"{module.name}: component group {data['component']['group']}")
    for variant in data.get("variants", []):
        at = variant.get("available-at")
        if at and at["group"] != group:
            bad.append(f"{module.name}: variant {variant['name']} at {at['group']}:{at['module']}")
        for dep in variant.get("dependencies", []):
            if dep["group"].startswith("com.steamstreet") and dep["group"] != group:
                bad.append(f"{module.name}: depends on {dep['group']}:{dep['module']}")

if not artifacts:
    print("no publications were produced", file=sys.stderr)
    sys.exit(1)

marker = f"{group}:{group}.gradle.plugin"
if marker not in artifacts:
    bad.append(f"the Gradle plugin marker {marker} was not produced")

for line in bad:
    print("    " + line, file=sys.stderr)
print(f"    {len(artifacts)} artifacts under {group} at {version}")
sys.exit(1 if bad else 0)
EOF

if [[ $DRY_RUN -eq 1 ]]; then
  say "Dry run complete"
  info "would have released $VERSION from $BRANCH"
  exit 0
fi

# --- 5. upload ---------------------------------------------------------------

say "Releasing $VERSION"
if ! confirm "Upload $VERSION to Sonatype and tag v$VERSION?"; then
  die "aborted before upload"
fi

# Staging repositories that already exist are not ours; remember them so the
# deployment this run creates can be identified without guessing.
PRE_EXISTING="$(api_get "$OSSRH_API/manual/search/repositories" \
  | python3 -c 'import json,sys; print(" ".join(r["key"] for r in json.load(sys.stdin).get("repositories",[])))')"
[[ -n "$PRE_EXISTING" ]] && info "note: staging repositories already exist and will be left alone"

./gradlew final ${SCOPE_ARG[@]+"${SCOPE_ARG[@]}"} --console=plain || \
  die "the release build failed; check whether artifacts were uploaded and whether v$VERSION was tagged before retrying"

git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null || die "release finished but tag v$VERSION was not created"
git ls-remote --tags origin "v$VERSION" | grep -q . || die "tag v$VERSION was not pushed to origin"
info "tagged and pushed v$VERSION"

# --- 6. validate -------------------------------------------------------------

say "Waiting for the deployment to validate"

DEPLOYMENTS=""
for _ in $(seq 1 30); do
  DEPLOYMENTS="$(api_get "$OSSRH_API/manual/search/repositories" | PRE="$PRE_EXISTING" VER="$VERSION" python3 -c '
import json, os, sys
pre = set(os.environ["PRE"].split())
ver = os.environ["VER"]
out = []
for r in json.load(sys.stdin).get("repositories", []):
    if r["key"] in pre:
        continue
    if not (r.get("description") or "").endswith(":" + ver):
        continue
    if r.get("portal_deployment_id"):
        out.append(r["portal_deployment_id"] + "=" + r["description"])
print(" ".join(out))')"
  [[ -n "$DEPLOYMENTS" ]] && break
  sleep 20
done

[[ -n "$DEPLOYMENTS" ]] || die "found no deployment for $VERSION"

# Emits "STATE<TAB>{errors json}" on one line, so this stays bash 3.2 compatible
# (macOS ships 3.2, which has no `mapfile`).
deployment_state() {
  api_post "$PORTAL_API/status?id=$1" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("%s\t%s" % (d.get("deploymentState", "UNKNOWN"), json.dumps(d.get("errors") or {})))'
}

for entry in $DEPLOYMENTS; do
  id="${entry%%=*}"; desc="${entry#*=}"
  state=""
  for _ in $(seq 1 60); do
    line="$(deployment_state "$id")"
    state="${line%%$'\t'*}"; errors="${line#*$'\t'}"
    case "$state" in
      VALIDATED|PUBLISHING|PUBLISHED) info "$desc -> $state"; break ;;
      FAILED) die "$desc FAILED validation: $errors" ;;
      *) sleep 20 ;;
    esac
  done
  case "$state" in
    VALIDATED|PUBLISHING|PUBLISHED) ;;
    *) die "$desc stuck in state $state" ;;
  esac
done

# --- 7. publish --------------------------------------------------------------

say "Publishing to Maven Central"
info "this cannot be undone; Central artifacts are never withdrawn"
for entry in $DEPLOYMENTS; do info "  ${entry#*=}"; done

if ! confirm "Publish $VERSION permanently?"; then
  info "left the deployment at VALIDATED; publish it at https://central.sonatype.com/publishing/deployments"
  exit 1
fi

for entry in $DEPLOYMENTS; do
  id="${entry%%=*}"; desc="${entry#*=}"
  line="$(deployment_state "$id")"; state="${line%%$'\t'*}"
  if [[ "$state" == "VALIDATED" ]]; then
    # Not `&& info ...`: under `set -e` a failing left-hand side of `&&` is exempt, so
    # a rejected publish would be skipped in silence.
    if api_post "$PORTAL_API/deployment/$id" >/dev/null; then
      info "published $desc"
    else
      die "publish request for $desc was rejected; it is still at VALIDATED"
    fi
  else
    info "skipped $desc (already $state)"
  fi
done

# --- 8. verify ---------------------------------------------------------------

say "Waiting for the artifacts to appear on repo1"
info "propagation usually takes 10-30 minutes"

GROUP_PATH="${GROUP//.//}"
PROBES="$GROUP_PATH/server/$VERSION $GROUP_PATH/server-jvm/$VERSION $GROUP_PATH/$GROUP.gradle.plugin/$VERSION"

missing=0
for _ in $(seq 1 40); do
  missing=0
  for path in $PROBES; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$REPO1/$path/")"
    [[ "$code" == "200" ]] || missing=1
  done
  [[ $missing -eq 0 ]] && break
  sleep 60
done

say "Release complete: $VERSION"
for path in $PROBES; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$REPO1/$path/")"
  info "HTTP $code  $REPO1/$path/"
done
[[ $missing -eq 0 ]] || info "not all artifacts are answering yet; propagation can lag, the deployment is published"
