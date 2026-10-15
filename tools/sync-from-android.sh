#!/usr/bin/env bash
#
# sync-from-android.sh — the one sanctioned way to move files out of the upstream Android app.
#
# The upstream tree is READ-ONLY (AGENTS.md §1). This script only ever reads from it: every
# command it runs against $UPSTREAM is cp/read, and it refuses to write anything whose
# destination lies inside that tree.
#
# Files arrive in GROUPS, not all at once. PLAN.md §7a explains why: half of the main COPY list
# is extension functions on AssistantService, so copying the whole list in one go produces a
# source set that cannot compile. The m0 group stands alone; the m1 group needs AssistantService.kt
# (and its Context -> AppEnv port) to exist first.
#
# There are three lists, and all of them follow the same rules:
#   COPY_SOURCES  main sources      -> core/src/main/kotlin/com/nikosm/voiceassistant/
#   COPY_DATA     data files        -> repo root / assets/
#   TEST_SOURCES  upstream tests    -> core/src/test/kotlin/com/nikosm/voiceassistant/
# A test file arrives byte-identical like everything else, which means --check covers it and an
# upstream improvement is later copyable as a small diff. A test that needs retargeting (the
# AndroidJUnit4 / WorkManager / Compose-device ones) is NOT added to TEST_SOURCES: it is adapted
# on purpose, stays out of this list, and gets a line in docs/TEST-MAP.md instead — the same
# demotion rule that applies to a COPY file (AGENTS.md §3), pointed at tests.
#
# This script also owns the PINNED upstream revision: core/src/main/resources/upstream-commit.txt,
# written on every successful copy and read by --selftest (BOOTSTRAP.md §5, M0.5). It is a
# checked-in one-line file rather than a build-time `git rev-parse`, so a fresh clone and the
# Debian container can print it without the upstream tree being present.
#
# There is a fourth list, PORT_SOURCES, and it is NOT a copy list. A PORT+ file (PLAN.md §7) is one
# that must change — `Context` to AppEnv, `File` to Path, Android audio to a seam — so it can never
# be byte-identical and can never be `--check`ed as if it were. But retyping 3,000 lines by hand
# throws away the only thing that makes a port reviewable: the diff. `--seed` places the upstream
# file verbatim as a STARTING COPY, and every adaptation after it is then a `git diff` against
# upstream, comment for comment. The seeding leaves a file that does not compile; that is the point,
# and it is why the mode is named differently from --group.
#
# Usage:
#   tools/sync-from-android.sh --group m0       copy the files M0 needs, main and test
#   tools/sync-from-android.sh --group m1       copy the files that arrive with AssistantService
#   tools/sync-from-android.sh --seed <name>    place one PORT+ file as a starting copy to adapt
#   tools/sync-from-android.sh --check          report drift, the pin, and what has not arrived yet
#   tools/sync-from-android.sh --list           print the groups, the test resolution and the pin
#
# Environment:
#   UPSTREAM   path to the upstream Android project (default: ../VoiceAssistant)

set -eu

UPSTREAM="${UPSTREAM:-../VoiceAssistant}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

UPSTREAM_APP="$UPSTREAM/app/src/main"
UPSTREAM_SRC="$UPSTREAM_APP/java/com/nikosm/voiceassistant"
DEST_SRC="$REPO_ROOT/core/src/main/kotlin/com/nikosm/voiceassistant"
DEST_TEST="$REPO_ROOT/core/src/test/kotlin/com/nikosm/voiceassistant"
PIN_FILE="$REPO_ROOT/core/src/main/resources/upstream-commit.txt"

# Upstream test trees, searched in order. A name found in more than one is resolved by this
# order, and --list prints the path it picked so a wrong pick is visible rather than silent.
UPSTREAM_TEST_DIRS=(
  "app/src/test/java/com/nikosm/voiceassistant"
  "app/src/test/kotlin/com/nikosm/voiceassistant"
  "app/src/androidTest/java/com/nikosm/voiceassistant"
)

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

# GROUP|upstream-name — pure JVM tests, byte-identical, same rule as above
TEST_SOURCES=(
  "m0|ComfyWorkflowValidationTest.kt"
  "m0|ComfyPromptFillingTest.kt"
  "m0|ComfyFixtures.kt"
  # The group is about what compiles together, not which milestone it belongs to: this one tests
  # a pure function and needs no service, so it travels with the m0 set even though it arrived
  # during M1. It is byte-identical — JUnit 4 only, no Android surface — and it is the executable
  # specification for the script detector that decides which TTS voice a reply is spoken in.
  "m0|ScriptLanguageDetectionTest.kt"
)

# PORT+ verdicts (PLAN.md §7) that are large enough that a diff beats a retype. These are seeded
# with --seed and then hand-adapted; they are expected to differ from upstream forever, so --check
# reports them but never counts them as drift. Every one must have a row in the adapted-files table
# of docs/PROVENANCE.md (AGENTS.md §3).
PORT_SOURCES=(
  "AssistantService.kt"
  "AssistantServiceChat.kt"
)

# GROUP|label|source|destination
COPY_DATA=(
  "m0|LICENSE|$UPSTREAM/LICENSE|$REPO_ROOT/LICENSE"
  "m1|assets/silero_vad.onnx|$UPSTREAM_APP/assets/silero_vad.onnx|$REPO_ROOT/assets/silero_vad.onnx"
)

MODE=""
GROUP=""
SEED=""
case "${1:-}" in
  "")         ;;
  --group)    MODE="copy"; GROUP="${2:-}"; [ -n "$GROUP" ] || { echo "--group needs m0 or m1" >&2; exit 2; } ;;
  --seed)     MODE="seed"; SEED="${2:-}"; [ -n "$SEED" ] || { echo "--seed needs a file name" >&2; exit 2; } ;;
  --check)    MODE="check" ;;
  --list)     MODE="list" ;;
  -h|--help)  sed -n '2,45p' "$0"; exit 0 ;;
  *)          echo "usage: $0 --group m0|m1 | --seed <name> | --check | --list" >&2; exit 2 ;;
esac

if [ -z "$MODE" ]; then
  cat >&2 <<'USAGE'
refusing to copy: a group is required.

  tools/sync-from-android.sh --group m0     the files that stand alone (PLAN.md §7a)
  tools/sync-from-android.sh --group m1     the files that need AssistantService.kt first

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

if [ "$MODE" = "seed" ]; then
  # The name must be one the plan classifies as PORT+, so this can never become a general-purpose
  # copy escape hatch around the group lists.
  listed=""
  for name in "${PORT_SOURCES[@]}"; do
    [ "$name" = "$SEED" ] && listed="yes"
  done
  if [ -z "$listed" ]; then
    echo "refusing to seed: '$SEED' is not a PORT+ file (PLAN.md §7)." >&2
    echo "PORT_SOURCES holds: ${PORT_SOURCES[*]}" >&2
    echo "A COPY file arrives with --group; anything else is written by hand." >&2
    exit 2
  fi

  src="$UPSTREAM_SRC/$SEED"
  dst="$DEST_SRC/$SEED"
  if [ ! -f "$src" ]; then
    echo "MISSING UPSTREAM  $SEED (looked at $src)" >&2
    exit 1
  fi
  # The one thing this mode must never do. A seed is a starting point; once adapted, the file is
  # the only copy of work that exists nowhere else, and clobbering it would delete a port.
  if [ -e "$dst" ]; then
    echo "refusing to seed: $dst already exists." >&2
    echo "A seed only ever creates a file. To see what the port has done to it:" >&2
    echo "  diff -u '$src' '$dst'" >&2
    exit 1
  fi

  mkdir -p "$(dirname "$dst")"
  cp -p "$src" "$dst"
  printf 'seeded            %s  <- %s\n' "$SEED" "${src#"$UPSTREAM"/}"
  cat <<SEEDED

That is a STARTING COPY, not a port. It is upstream's file verbatim and it does not compile.

  1. It is PORT+ (PLAN.md §7), so it must change: Context -> AppEnv, Log -> the project logger,
     File -> Path, ProcessBuilder nowhere in :core, Android audio to a seam.
  2. Keep the comments and the constants. They record behaviour tuned against real use
     (PLAN.md §6), and a diff that deletes one is a diff that has to say why.
  3. Record the file in the adapted-files table of docs/PROVENANCE.md in the SAME commit.
  4. From here on, review it with:  diff -u '$src' '$dst'
SEEDED
  exit 0
fi

problems=0
taken=0; waiting=0
taken_test=0; waiting_test=0
in_test=0

upstream_test_path() {
  # $1 = file name. Prints the first path that exists, in UPSTREAM_TEST_DIRS order.
  for d in "${UPSTREAM_TEST_DIRS[@]}"; do
    if [ -f "$UPSTREAM/$d/$1" ]; then
      printf '%s\n' "$UPSTREAM/$d/$1"
      return 0
    fi
  done
  return 1
}

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
        if [ "$in_test" = 1 ]; then waiting_test=$((waiting_test + 1)); else waiting=$((waiting + 1)); fi
      elif cmp -s "$src" "$dst"; then
        printf 'ok                %s\n' "$label"
        if [ "$in_test" = 1 ]; then taken_test=$((taken_test + 1)); else taken=$((taken + 1)); fi
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

in_test=1
for entry in "${TEST_SOURCES[@]}"; do
  grp="${entry%%|*}"; name="${entry#*|}"
  if ! src="$(upstream_test_path "$name")"; then
    if [ "$MODE" = "copy" ] && [ "$grp" = "$GROUP" ]; then
      echo "MISSING UPSTREAM  $name (searched: ${UPSTREAM_TEST_DIRS[*]})" >&2
      problems=$((problems + 1))
    elif [ "$MODE" = "check" ]; then
      printf 'SKIP (absent upstream)  %s\n' "$name"
    fi
    continue
  fi
  if [ "$MODE" = "list" ]; then
    printf '%s  %s  <- %s\n' "$grp" "$name" "${src#"$UPSTREAM"/}"
  else
    sync_one "$grp" "$name" "$src" "$DEST_TEST/$name"
  fi
done

# PORT+ files: reported, never counted. They are supposed to differ from upstream — that is what
# "ported" means — so listing them beside the COPY files serves only to say which ones have started
# and to keep their existence in front of whoever runs --check.
if [ "$MODE" = "list" ] || [ "$MODE" = "check" ]; then
  for name in "${PORT_SOURCES[@]}"; do
    if [ "$MODE" = "list" ]; then
      printf 'PORT+  %s\n' "$name"
    elif [ -f "$DEST_SRC/$name" ]; then
      printf 'ported            %s  (diff against upstream, never byte-identical)\n' "$name"
    else
      printf 'not yet ported    %s\n' "$name"
    fi
  done
fi

echo
case "$MODE" in
  list)
    echo "m0 = the files that stand alone (PLAN.md §7a), main and test"
    echo "m1 = the files that need AssistantService.kt to exist first"
    echo "a test that needs retargeting stays OFF this list and goes in docs/TEST-MAP.md"
    echo "PORT+ = seeded with --seed, then adapted by hand; never byte-identical, never drift"
    if [ -f "$PIN_FILE" ]; then
      echo "pin  $(tr -d '\r\n' < "$PIN_FILE")  ($PIN_FILE, read by --selftest)"
    else
      echo "pin  not written yet — a successful --group run establishes it"
    fi
    ;;
  copy)
    if [ "$problems" -gt 0 ]; then
      echo "$problems file(s) could not be copied" >&2
      exit 1
    fi
    echo "group $GROUP copied from upstream commit $UPSTREAM_COMMIT"
    mkdir -p "$(dirname "$PIN_FILE")"
    printf '%s\n' "$UPSTREAM_COMMIT" > "$PIN_FILE"
    echo "pin written      $PIN_FILE"
    echo "record in docs/PROVENANCE.md only files taken at an OLDER commit than the pin"
    if [ "$GROUP" = "m0" ]; then
      cat <<'NEXT'
m0 still needs, hand-written rather than copied (PLAN.md §7a) — and :core must pin ui-graphics to the :desktop version:
  core/src/main/kotlin/android/util/Log.kt          the Log shim — the only android.* package here
  core/src/main/kotlin/.../GatewayUrl.kt           BORROWED FROM M1 - normalizeGatewayUrl
  core/src/main/kotlin/.../ImageGenConstants.kt    BORROWED FROM M1 - IMAGE_GEN_NEGATIVE_PROMPT
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
    if [ -f "$PIN_FILE" ]; then
      PINNED="$(tr -d '\r\n' < "$PIN_FILE")"
      if [ "$PINNED" = "$UPSTREAM_COMMIT" ]; then
        echo "pin: $PINNED (matches upstream HEAD)"
      else
        echo "pin: $PINNED — upstream HEAD is $UPSTREAM_COMMIT. A note, not a fault:"
        echo "     upstream moving is allowed, the copied files staying byte-identical is the guard."
      fi
    else
      echo "pin: not written yet — run --group m0 once to establish it"
    fi
    if [ "$problems" -eq 0 ]; then
      echo "no drift: main $taken taken / $waiting to arrive, test $taken_test taken / $waiting_test to arrive"
      echo "(PORT+ files are not counted: they are ported on purpose and cannot be byte-identical)"
      exit 0
    fi
    echo "$problems file(s) differ from upstream — COPY files must stay byte-identical" >&2
    exit 1
    ;;
esac
