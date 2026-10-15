#!/usr/bin/env bash
#
# sync-from-android.sh — the one sanctioned way to move files out of the upstream Android app.
#
# The upstream tree is READ-ONLY (AGENTS.md §1). This script only ever reads from it: every
# command it runs against $UPSTREAM is cp/read, and it refuses to write anything whose
# destination lies inside that tree.
#
# Files arrive in GROUPS, not all at once. PLAN.md §7a explains why: half the COPY list is
# extension functions on AssistantService, so copying the whole list in one go produces a source
# set that cannot compile. The m0 group stands alone; the m1 group needs AssistantService.kt
# (and its Context -> AppEnv port) to exist first.
#
# COPY files must stay byte-identical to upstream. The android.util.Log shim (PLAN.md §7a) exists
# so that a logging call never forces one of them to be edited. If a task really needs a COPY file
# changed, that is a deliberate demotion: edit PLAN.md §7, add a docs/PROVENANCE.md entry, and take
# the file out of this list — in the same commit.
#
# Usage:
#   tools/sync-from-android.sh --group m0       copy the files M0 needs
#   tools/sync-from-android.sh --group m1       copy the files that arrive with AssistantService
#   tools/sync-from-android.sh --check          report drift, and which files have not arrived yet
#   tools/sync-from-android.sh --list           print the groups and the upstream commit
#
# Environment:
#   UPSTREAM   path to the upstream Android project (default: ../VoiceAssistant)

set -eu

UPSTREAM="${UPSTREAM:-../VoiceAssistant}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

UPSTREAM_APP="$UPSTREAM/app/src/main"
UPSTREAM_SRC="$UPSTREAM_APP/java/com/nikosm/voiceassistant"
DEST_SRC="$REPO_ROOT/core/src/main/kotlin/com/nikosm/voiceassistant"

# GROUP|upstream-name   — groups are arrival milestones, PLAN.md §7a
COPY_SOURCES=(
  "m0|ModelCatalog.kt"
  "m0|ComfyJobs.kt"
  "m0|ComfyWorkflow.kt"
  "m0|MessageTimestamps.kt"
  "m0|SpontaneousScheduler.kt"
  "m0|ServerIdentity.kt"
  "m0|Models.kt"
  "m1|AssistantServiceComfy.kt"
  "m1|AssistantServiceRooms.kt"
  "m1|AssistantServiceRoomTurns.kt"
  "m1|AssistantServiceSpontaneous.kt"
  "m1|AssistantServiceImageGen.kt"
  "m1|TurnSink.kt"
)

# GROUP|label|source|destination
COPY_DATA=(
  "m0|LICENSE|$UPSTREAM/LICENSE|$REPO_ROOT/LICENSE"
  "m1|assets/silero_vad.onnx|$UPSTREAM_APP/assets/silero_vad.onnx|$REPO_ROOT/assets/silero_vad.onnx"
)

MODE=""
GROUP=""
case "${1:-}" in
  "")         ;;
  --group)    MODE="copy"; GROUP="${2:-}"; [ -n "$GROUP" ] || { echo "--group needs m0 or m1" >&2; exit 2; } ;;
  --check)    MODE="check" ;;
  --list)     MODE="list" ;;
  -h|--help)  sed -n '2,27p' "$0"; exit 0 ;;
  *)          echo "usage: $0 --group m0|m1 | --check | --list" >&2; exit 2 ;;
esac

if [ -z "$MODE" ]; then
  cat >&2 <<'USAGE'
refusing to copy: a group is required.

  tools/sync-from-android.sh --group m0     the six files that stand alone (PLAN.md §7a)
  tools/sync-from-android.sh --group m1     the six that need AssistantService.kt first

Copying a file whose dependency has not been ported yet leaves :core unable to compile, which is
exactly what the group split exists to prevent. --check and --list need no group.
USAGE
  exit 2
fi

case "$GROUP" in
  ""|m0|m1) ;;
  *) echo "--group takes m0 or m1, not '$GROUP'" >&2; exit 2 ;;
esac

# Safety: never point this at a destination inside the upstream tree.
case "$REPO_ROOT/" in
  "$UPSTREAM"/*)
    echo "refusing to run: destination $REPO_ROOT is inside the upstream tree $UPSTREAM" >&2
    exit 1
    ;;
esac

if [ ! -d "$UPSTREAM_SRC" ]; then
  echo "upstream sources not found at $UPSTREAM_SRC" >&2
  echo "set UPSTREAM=/path/to/VoiceAssistant if it lives elsewhere" >&2
  exit 1
fi

if [ -d "$UPSTREAM/.git" ]; then
  UPSTREAM_COMMIT="$(git -C "$UPSTREAM" rev-parse HEAD)"
else
  UPSTREAM_COMMIT="(not a git checkout)"
fi

echo "upstream : $UPSTREAM"
echo "commit   : $UPSTREAM_COMMIT"
echo "mode     : ${MODE}${GROUP:+ (group $GROUP)}"
echo

problems=0
taken=0
waiting=0

sync_one() {
  # $1 = group, $2 = label, $3 = source, $4 = destination
  grp="$1"; label="$2"; src="$3"; dst="$4"

  case "$MODE" in
    list)
      printf '%s  %s\n' "$grp" "$label"
      return 0
      ;;
    copy)
      [ "$grp" = "$GROUP" ] || return 0
      if [ ! -f "$src" ]; then
        echo "MISSING UPSTREAM  $label" >&2
        problems=$((problems + 1))
        return 0
      fi
      mkdir -p "$(dirname "$dst")"
      cp -p "$src" "$dst"
      printf 'copied            %s\n' "$label"
      return 0
      ;;
    check)
      if [ ! -f "$src" ]; then
        printf 'SKIP (absent upstream)  %s\n' "$label"
        return 0
      fi
      if [ ! -f "$dst" ]; then
        # Expected in m0 for the m1 group: not a fault, just not taken yet.
        printf 'not yet taken (%s)  %s\n' "$grp" "$label"
        waiting=$((waiting + 1))
      elif cmp -s "$src" "$dst"; then
        printf 'ok                %s\n' "$label"
        taken=$((taken + 1))
      else
        printf 'DIFFERS           %s\n' "$label"
        diff -u "$dst" "$src" | head -40 || true
        problems=$((problems + 1))
      fi
      return 0
      ;;
  esac
}

for entry in "${COPY_SOURCES[@]}"; do
  grp="${entry%%|*}"; name="${entry#*|}"
  sync_one "$grp" "$name" "$UPSTREAM_SRC/$name" "$DEST_SRC/$name"
done

for entry in "${COPY_DATA[@]}"; do
  grp="${entry%%|*}"; rest="${entry#*|}"
  label="${rest%%|*}"; rest="${rest#*|}"
  src="${rest%%|*}"; dst="${rest##*|}"
  sync_one "$grp" "$label" "$src" "$dst"
done

echo
case "$MODE" in
  list)
    echo "m0 = seven files, 3,030 lines — they stand alone (PLAN.md §7a)"
    echo "m1 = six files, 2,388 lines — they need AssistantService.kt to exist first"
    ;;
  copy)
    if [ "$problems" -gt 0 ]; then
      echo "$problems file(s) could not be copied" >&2
      exit 1
    fi
    echo "group $GROUP copied from upstream commit $UPSTREAM_COMMIT"
    echo "record that hash in docs/PROVENANCE.md for every file you took"
    if [ "$GROUP" = "m0" ]; then
      cat <<'NEXT'
m0 still needs, hand-written rather than copied (PLAN.md §7a) — and :core must pin ui-graphics to the :desktop version:
  core/src/main/kotlin/android/util/Log.kt          the Log shim — the only android.* package here
  core/src/main/kotlin/.../BorrowedFromM1.kt      normalizeGatewayUrl + the shared negative prompt; both deleted in M1
NEXT
    fi
    if [ "$GROUP" = "m1" ]; then
      if [ -f "$DEST_SRC/AssistantService.kt" ]; then
        echo "AssistantService.kt is present — the m1 files have something to extend"
      else
        echo "WARNING: core/ has no AssistantService.kt yet, so these files will not compile" >&2
        echo "         expected if you are mid-port; do not commit a broken :core" >&2
      fi
    fi
    ;;
  check)
    if [ "$problems" -eq 0 ]; then
      echo "no drift: $taken file(s) taken and byte-identical, $waiting still to arrive"
      exit 0
    fi
    echo "$problems file(s) differ from upstream — COPY files must stay byte-identical" >&2
    exit 1
    ;;
esac
