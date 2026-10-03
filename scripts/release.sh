#!/usr/bin/env bash
#
# Cuts a release of GraphKt. By default it publishes to the Steamstreet repository only, which
# answers at https://repo.steamstreet.com within a minute or two of the upload. `--target central`
# also publishes to Maven Central, which takes about two hours to reach repo1, for the occasional
# public release. Either way the Steamstreet repository gets every version. docs/releasing.md
# describes the whole process.
#
# Usage:
#   scripts/release.sh [--target steamstreet|central] [--check full|jvm|none]
#                      [--scope patch|minor|major] [--yes] [--dry-run]
#   scripts/release.sh --resume [--yes]
#
# Run it from a release branch (`3.0.x`, `3.1.x`, ...). Nebula derives the version from the
# latest tag: a patch bump by default, or the bump that --scope names. A release takes longer than
# 30 minutes, so run it from a shell, or from a tool whose time limit is at least an hour.
#
# `--resume` publishes the version tagged at HEAD to the Steamstreet repository again, without a
# check and without tagging: to finish a release whose tag was pushed before its uploads completed,
# or to backfill an older tag into the repository (check the tag out first). Uploads of a version
# replace the same coordinates, so running it twice is harmless.
#
# `--check` sets how much is verified before anything is uploaded:
#   jvm   the JVM tests only (jvmTest in the multiplatform modules, test in the JVM-only ones),
#         without a clean. The default for --target steamstreet.
#   full  a clean `check`: every target that can build on this host compiled and tested. The
#         default for --target central, whose releases are public and permanent.
#   none  nothing. `--skip-check` is the same.
# Whatever the level, the coordinate check below compiles every target to publish it, so a native
# compile error still stops the release; `jvm` gives up the native and JavaScript tests.
#
# Publishing to the Steamstreet repository uses the AWS profile named by GRAPHKT_PUBLISH_PROFILE,
# `steamstreet-publisher` by default: the access key of the IAM user steamstreet-maven-publisher,
# which can only read, write and list the bucket's Maven tree. A static key rather than an SSO
# profile, so that a release never waits on a login.
#
# The steps for `--target steamstreet`:
#   1. preflight   — clean tree, branch in sync with origin, publishing profile works
#   2. version     — asked of nebula rather than assumed
#   3. check       — the JVM tests by default
#   4. coordinates — publishes to a scratch Maven repository and refuses any artifact outside
#                    the `com.steamstreet.graphkt` group, or a missing plugin marker
#   5. upload      — every module, the Gradle plugin and its marker
#   6. verify      — every POM from step 4 answers at repo.steamstreet.com
#   7. tag         — only now, so that a tag means a complete release
# A release interrupted before the tag leaves nothing to clean up: run it again and it re-uploads.
# It does not use nebula's `final` task, which pushes the tag before the uploads finish; that left
# awskt 3.1.6 tagged and partly published when its first run was cut off.
#
# The steps for `--target central`:
#   1-4.           as above, with Central's credentials also required, and a clean `check`
#   5. final       — uploads every module, including the Gradle plugin and its marker, to both
#                    Sonatype and the Steamstreet repository, closes the staging repository,
#                    tags, pushes
#   6. validate    — waits for the deployment to reach VALIDATED
#   7. publish     — the irreversible step, confirmed unless --yes
#   8. verify      — waits for PUBLISHED and for the artifacts to answer on repo1
#
# `--dry-run` stops after step 4, before anything is uploaded or tagged.
#
# Two things the Central path exists to prevent, both of which have happened to awskt, which
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
STEAMSTREET_REPO="https://repo.steamstreet.com"
STEAMSTREET_ACCOUNT="141660060409"
PUBLISH_PROFILE="${GRAPHKT_PUBLISH_PROFILE:-steamstreet-publisher}"
GROUP="com.steamstreet.graphkt"

TARGET="steamstreet"
CHECK=""
SCOPE=""
ASSUME_YES=0
DRY_RUN=0
RESUME=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)     TARGET="${2:-}"; shift 2 ;;
    --target=*)   TARGET="${1#*=}"; shift ;;
    --check)      CHECK="${2:-}"; shift 2 ;;
    --check=*)    CHECK="${1#*=}"; shift ;;
    --scope)      SCOPE="${2:-}"; shift 2 ;;
    --scope=*)    SCOPE="${1#*=}"; shift ;;
    --yes|-y)     ASSUME_YES=1; shift ;;
    --dry-run)    DRY_RUN=1; shift ;;
    --resume)     RESUME=1; shift ;;
    --skip-check) CHECK="none"; shift ;;
    -h|--help)    sed -n '2,70p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)            echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$TARGET" =~ ^(steamstreet|central)$ ]]; then
  echo "--target must be steamstreet or central" >&2
  exit 2
fi

if [[ $RESUME -eq 1 && "$TARGET" != "steamstreet" ]]; then
  echo "--resume publishes to the Steamstreet repository only; a Central release cannot be resumed this way" >&2
  exit 2
fi

if [[ $RESUME -eq 1 && -n "$SCOPE" ]]; then
  echo "--resume publishes the version tagged at HEAD; --scope does not apply" >&2
  exit 2
fi

if [[ -z "$CHECK" ]]; then
  if [[ "$TARGET" == "central" ]]; then CHECK="full"; else CHECK="jvm"; fi
fi
if [[ ! "$CHECK" =~ ^(full|jvm|none)$ ]]; then
  echo "--check must be full, jvm or none" >&2
  exit 2
fi

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
if [[ $RESUME -eq 1 ]]; then
  # Resuming publishes a commit that is already tagged and pushed, so it need not be a branch tip.
  RESUME_TAG="$(git tag --points-at HEAD | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1 || true)"
  [[ -n "$RESUME_TAG" ]] || die "--resume needs HEAD to carry a release tag (vX.Y.Z); check the tag out first"
  git ls-remote --tags origin "$RESUME_TAG" | grep -q . || die "$RESUME_TAG is not on origin"
else
  [[ "$BRANCH" =~ ^[0-9]+\.[0-9]+\.x$ ]] || die "releases are cut from a release branch such as 3.0.x, not '$BRANCH'"

  git fetch --quiet --tags origin "$BRANCH" || die "could not fetch origin/$BRANCH"

  if [[ -n "$(git rev-list "origin/$BRANCH..HEAD")" ]]; then
    die "$BRANCH has unpushed commits; push them first so the tag refers to a commit on origin"
  fi
  if [[ -n "$(git rev-list "HEAD..origin/$BRANCH")" ]]; then
    die "$BRANCH is behind origin/$BRANCH; pull first"
  fi
fi

GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
read_prop() { grep -E "^$1=" "$GRADLE_PROPS" 2>/dev/null | head -1 | cut -d= -f2- || true; }

# The publishing profile is handed to the publishing steps alone, as AWS_PROFILE, with any keys the
# shell has removed so that they cannot take precedence over it. It holds a static key, which the AWS
# SDK inside Gradle reads from ~/.aws/credentials; an SSO profile would not work there.
#
# It is never exported as AWS_ACCESS_KEY_ID. awskt's live tests run whenever that variable is set,
# and with the publisher's key, which can only write the Maven bucket, they failed and stopped
# awskt's first release made this way. GraphKt has no such tests today, but the checks below run
# with no AWS credentials at all, so they stay offline whatever the shell holds.
command -v aws >/dev/null || die "the AWS CLI is required to publish to the Steamstreet repository"
publishing() {
  env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
    AWS_PROFILE="$PUBLISH_PROFILE" "$@"
}
offline() {
  env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN -u AWS_PROFILE "$@"
}
PUBLISH_ACCOUNT="$(publishing aws sts get-caller-identity --query Account --output text 2>/dev/null)" || \
  die "no working credentials in AWS profile '$PUBLISH_PROFILE'; see docs/releasing.md"
[[ "$PUBLISH_ACCOUNT" == "$STEAMSTREET_ACCOUNT" ]] || \
  die "profile '$PUBLISH_PROFILE' is in account $PUBLISH_ACCOUNT, not the Steamstreet account $STEAMSTREET_ACCOUNT"

if [[ "$TARGET" == "central" && $DRY_RUN -eq 0 ]]; then
  CENTRAL_USER="${MAVEN_CENTRAL_USERNAME:-$(read_prop mavenCentralUsername)}"
  CENTRAL_PASS="${MAVEN_CENTRAL_PASSWORD:-$(read_prop mavenCentralPassword)}"

  [[ -n "$CENTRAL_USER" && -n "$CENTRAL_PASS" ]] || \
    die "mavenCentralUsername/mavenCentralPassword not found in $GRADLE_PROPS"
  [[ -n "$(read_prop signing.keyId)" ]] || \
    die "signing.keyId not found in $GRADLE_PROPS; Central rejects unsigned artifacts"

  TOKEN="$(printf '%s:%s' "$CENTRAL_USER" "$CENTRAL_PASS" | base64)"
fi

info "target:      $TARGET"
info "check:       $CHECK"
if [[ $RESUME -eq 1 ]]; then
  info "resuming:    $RESUME_TAG at HEAD"
else
  info "branch:      $BRANCH (in sync with origin)"
fi
info "publishing:  AWS profile $PUBLISH_PROFILE"
[[ "$TARGET" == "central" && $DRY_RUN -eq 0 ]] && info "central:     credentials present"

api_get()  { curl -fsS -u "$CENTRAL_USER:$CENTRAL_PASS" -H "Accept: application/json" --max-time 120 "$@"; }
api_post() { curl -fsS -X POST -H "Authorization: Bearer $TOKEN" --max-time 300 "$@"; }

# --- 2. version --------------------------------------------------------------

say "Resolving version"

# Expanded below as ${SCOPE_ARG[@]+...}, never plainly: macOS ships bash 3.2, which under `set -u`
# treats an empty array's "${SCOPE_ARG[@]}" as an unbound variable and exits.
SCOPE_ARG=()
[[ -n "$SCOPE" ]] && SCOPE_ARG=(-Prelease.scope="$SCOPE")

# Every Gradle run that builds the release passes these, so they all agree on the version. When
# resuming, nebula takes it from the tag at HEAD instead of inferring the next one.
if [[ $RESUME -eq 1 ]]; then
  VERSION_ARGS=(-Prelease.useLastTag=true -Prelease.stage=final)
else
  VERSION_ARGS=(-Prelease.stage=final ${SCOPE_ARG[@]+"${SCOPE_ARG[@]}"})
fi

VERSION="$(./gradlew properties "${VERSION_ARGS[@]}" --console=plain -q 2>/dev/null \
  | grep -E '^version:' | head -1 | awk '{print $2}')"

[[ -n "$VERSION" ]] || die "could not determine the version nebula would use; check --scope against the branch name"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
  die "resolved version '$VERSION' is not a release version; check --scope and the branch name"

if [[ $RESUME -eq 1 ]]; then
  [[ "v$VERSION" == "$RESUME_TAG" ]] || die "nebula resolved $VERSION, but HEAD is tagged $RESUME_TAG"
  CHECK="none"
else
  git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && \
    die "tag v$VERSION already exists; this version has been released (use --resume to finish publishing it)"
fi

info "version: $VERSION"

# --- 3. check ----------------------------------------------------------------

case "$CHECK" in
  none)
    say "Skipping the check (--check none)"
    ;;
  jvm)
    say "Running the JVM tests"
    info "jvmTest in the multiplatform modules and test in the JVM-only ones; native and JavaScript tests are not run"
    offline ./gradlew jvmTest test --console=plain || die "the JVM tests failed; nothing has been uploaded"
    ;;
  full)
    say "Running clean check"
    info "every target that can build on this host is compiled and tested"
    offline ./gradlew clean check --console=plain || die "check failed; nothing has been uploaded"
    ;;
esac

# --- 4. coordinates ----------------------------------------------------------

# Every coordinate, in the POMs and in the Gradle module metadata that points consumers at the
# per-target variants, has to sit under one group. awskt 3.0.0 shipped a split namespace because a
# rewritten artifactId was silently overwritten for the target publications, and Central coordinates
# cannot be withdrawn, so this is checked on the real publications before anything is uploaded. The
# POMs found here are also the list that step 6 expects to answer at repo.steamstreet.com.
say "Checking published coordinates"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/graphkt-release.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
STAGE="$WORK/m2"
POMS="$WORK/poms.txt"

offline ./gradlew publishToMavenLocal "${VERSION_ARGS[@]}" \
  -Dmaven.repo.local="$STAGE" --console=plain -q || die "publishing to a scratch repository failed"

STAGE="$STAGE" POMS="$POMS" GROUP="$GROUP" VERSION="$VERSION" python3 - <<'EOF' || die "published coordinates are inconsistent; nothing has been uploaded"
import json, os, re, sys, pathlib

stage, group, version = pathlib.Path(os.environ["STAGE"]), os.environ["GROUP"], os.environ["VERSION"]
bad, artifacts, paths = [], [], []

for pom in sorted(stage.rglob("*.pom")):
    text = pom.read_text()
    g = re.search(r"<project[^>]*>.*?<groupId>([^<]+)</groupId>", text, re.S).group(1)
    a = re.search(r"</groupId>\s*<artifactId>([^<]+)</artifactId>", text, re.S).group(1)
    v = re.search(r"</artifactId>\s*<version>([^<]+)</version>", text, re.S).group(1)
    artifacts.append(f"{g}:{a}")
    paths.append(pom.relative_to(stage).as_posix())
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

pathlib.Path(os.environ["POMS"]).write_text("\n".join(paths) + "\n")

for line in bad:
    print("    " + line, file=sys.stderr)
print(f"    {len(artifacts)} artifacts under {group} at {version}")
sys.exit(1 if bad else 0)
EOF

if [[ $DRY_RUN -eq 1 ]]; then
  say "Dry run complete"
  if [[ $RESUME -eq 1 ]]; then
    info "would have published $VERSION ($RESUME_TAG) to $STEAMSTREET_REPO again"
  else
    info "would have released $VERSION from $BRANCH to $TARGET"
  fi
  exit 0
fi

# Waits for every POM that step 4 produced to answer 200 at the Steamstreet repository, asking again
# only for those still missing. CloudFront holds a 404 for about ten seconds, so a path asked for a
# moment before its upload landed answers again shortly after.
verify_steamstreet() {
  local pending missing path code total
  pending="$(cat "$POMS")"
  total="$(wc -l <"$POMS" | tr -d ' ')"
  for _ in $(seq 1 18); do
    missing=""
    for path in $pending; do
      code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$STEAMSTREET_REPO/$path")"
      [[ "$code" == "200" ]] || missing="$missing $path"
    done
    pending="$missing"
    [[ -z "$pending" ]] && break
    sleep 10
  done
  if [[ -z "$pending" ]]; then
    info "all $total POMs answer at $STEAMSTREET_REPO"
    return 0
  fi
  for path in $pending; do
    info "missing  $STEAMSTREET_REPO/$path"
  done
  return 1
}

if [[ "$TARGET" == "steamstreet" ]]; then
  # --- 5. upload -------------------------------------------------------------

  if [[ $RESUME -eq 1 ]]; then
    say "Publishing $VERSION to the Steamstreet repository again (--resume)"
    confirm "Upload $VERSION, already tagged, to $STEAMSTREET_REPO?" || die "aborted before upload"
  else
    say "Releasing $VERSION to the Steamstreet repository"
    confirm "Upload $VERSION to $STEAMSTREET_REPO, then tag v$VERSION?" || die "aborted before upload"
  fi

  info "every module, the Gradle plugin and its marker"
  publishing ./gradlew publishAllPublicationsToSteamstreetRepository "${VERSION_ARGS[@]}" --console=plain || \
    die "the upload failed; nothing was tagged, so run the release again to finish it"

  # --- 6. verify -------------------------------------------------------------

  say "Checking the artifacts answer at $STEAMSTREET_REPO"
  verify_steamstreet || \
    die "not every POM answered; nothing was tagged. Check s3://steamstreet-repository/maven/release"

  # --- 7. tag ----------------------------------------------------------------

  if [[ $RESUME -eq 0 ]]; then
    # Tagged last, so that a tag means a complete release. The message matches nebula's own tags.
    say "Tagging v$VERSION"
    git tag -a "v$VERSION" -m "Release of $VERSION" || die "could not create tag v$VERSION"
    git push origin "v$VERSION" || die "could not push tag v$VERSION; push it by hand"
    info "tagged and pushed v$VERSION"
  fi

  say "Release complete: $VERSION"
  exit 0
fi

# --- 5. final ----------------------------------------------------------------

say "Releasing $VERSION"
if ! confirm "Upload $VERSION to Sonatype and $STEAMSTREET_REPO, and tag v$VERSION?"; then
  die "aborted before upload"
fi

# Staging repositories that already exist are not ours; remember them so the
# deployment this run creates can be identified without guessing.
PRE_EXISTING="$(api_get "$OSSRH_API/manual/search/repositories" \
  | python3 -c 'import json,sys; print(" ".join(r["key"] for r in json.load(sys.stdin).get("repositories",[])))')"
[[ -n "$PRE_EXISTING" ]] && info "note: staging repositories already exist and will be left alone"

publishing ./gradlew final -Pgraphkt.publishTarget=central ${SCOPE_ARG[@]+"${SCOPE_ARG[@]}"} --console=plain || \
  die "the release build failed; check whether artifacts were uploaded and whether v$VERSION was tagged before retrying"

git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null || die "release finished but tag v$VERSION was not created"
git ls-remote --tags origin "v$VERSION" | grep -q . || die "tag v$VERSION was not pushed to origin"
info "tagged and pushed v$VERSION"

say "Checking the artifacts answer at $STEAMSTREET_REPO"
verify_steamstreet || \
  info "not every POM answered at $STEAMSTREET_REPO; Central continues regardless, and --resume from v$VERSION fills the gap"

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
