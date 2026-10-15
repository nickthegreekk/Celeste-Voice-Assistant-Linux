#!/usr/bin/env bash
#
# sync-from-android.sh — the one sanctioned way to move files out of the upstream Android app.
#
# The upstream tree is READ-ONLY (AGENTS.md §1). This script only ever reads from it: every
# command it runs against $UPSTREAM is cp/read, and it refuses to write anything whose
# destination lies inside that tree.
#
# The file list below is the COPY column of PLAN.md §7. Those files must stay byte-identical to
# upstream. If a task needs one of them changed, that is a deliberate demotion: edit PLAN.md §7
# and docs/PROVENANCE.md in the same commit, and remove the file from this list.
#
# Usage:
#   tools/sync-from-android.sh            copy the listed files into core/ (and LICENSE, VAD model)
#   tools/sync-from-android.sh --check    report differences only; exit 1 if anything differs
#   tools/sync-from-android.sh --list     print the list and the upstream commit, change nothing
#
# Environment:
#   UPSTREAM   path to the upstream Android project (default: ../VoiceAssistant)

set -eu

UPSTREAM="${UPSTREAM:-../VoiceAssistant}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

UPSTREAM_APP="$UPSTREAM/app/src/main"
UPSTREAM_SRC="$UPSTREAM_APP/java/com/nikosm/voiceassistant"
DEST_SRC="$REPO_ROOT/core/src/main/kotlin/com/nikosm/voiceassistant"

# PLAN.md §7, verdict COPY. Format: upstream-name
COPY_SOURCES=(
  ModelCatalog.kt
  AssistantServiceRoomTurns.kt
  AssistantServiceSpontaneous.kt
  SpontaneousScheduler.kt
  AssistantServiceImageGen.kt
  ComfyJobs.kt
  AssistantServiceComfy.kt
  AssistantServiceRooms.kt
  ComfyWorkflow.kt
  MessageTimestamps.kt
  ServerIdentity.kt
  TurnSink.kt
)

# Other byte-identical files. Format: label|source|destination
COPY_DATA=(
  "LICENSE|$UPSTREAM/LICENSE|$REPO_ROOT/LICENSE"
  "assets/silero_vad.onnx|$UPSTREAM_APP/assets/silero_vad.onnx|$REPO_ROOT/assets/silero_vad.onnx"
)

MODE="copy"
case "${1:-}" in
  "")        MODE="copy" ;;
  --check)   MODE="check" ;;
  --list)    MODE="list" ;;
  -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
  *)         echo "usage: $0 [--check|--list]" >&2; exit 2 ;;
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
echo "mode     : $MODE"
echo

problems=0

sync_one() {
  # $1 = label, $2 = source file, $3 = destination file
  label="$1"; src="$2"; dst="$3"

  if [ ! -f "$src" ]; then
    if [ "$MODE" = "copy" ]; then
      echo "MISSING UPSTREAM  $label" >&2
      problems=$((problems + 1))
    else
      echo "SKIP (absent)     $label"
    fi
    return 0
  fi

  case "$MODE" in
    list)
      printf '%s\n' "$label"
      ;;
    copy)
      mkdir -p "$(dirname "$dst")"
      cp -p "$src" "$dst"
      printf 'copied            %s\n' "$label"
      ;;
    check)
      if [ ! -f "$dst" ]; then
        printf 'ABSENT IN REPO    %s\n' "$label"
        problems=$((problems + 1))
      elif cmp -s "$src" "$dst"; then
        printf 'ok                %s\n' "$label"
      else
        printf 'DIFFERS           %s\n' "$label"
        diff -u "$dst" "$src" | head -40 || true
        problems=$((problems + 1))
      fi
      ;;
  esac
}

for f in "${COPY_SOURCES[@]}"; do
  sync_one "$f" "$UPSTREAM_SRC/$f" "$DEST_SRC/$f"
done

for entry in "${COPY_DATA[@]}"; do
  label="${entry%%|*}"
  rest="${entry#*|}"
  src="${rest%%|*}"
  dst="${rest##*|}"
  sync_one "$label" "$src" "$dst"
done

echo
if [ "$MODE" = "check" ]; then
  if [ "$problems" -eq 0 ]; then
    echo "all COPY files match upstream at $UPSTREAM_COMMIT"
    exit 0
  fi
  echo "$problems file(s) differ from upstream — COPY files must stay byte-identical" >&2
  exit 1
fi

if [ "$MODE" = "copy" ]; then
  echo "copied with upstream commit $UPSTREAM_COMMIT"
  echo "record that hash in docs/PROVENANCE.md for every file you took"
fi
