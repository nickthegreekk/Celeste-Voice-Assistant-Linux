#!/usr/bin/env bash
# ledger.sh — the port's per-file ledger against upstream, and the arithmetic that proves it.
#
# The table in docs/PROVENANCE.md is an audit of what the port did to every file it took. An audit
# that does not reconcile is worse than none, because it gets read as though it did — which is how
# this script came to exist: the first version of that table had a hand-typed subtotal that was
# wrong by 4,001 lines and nobody would have caught it by reading.
#
# So the table is generated rather than transcribed, and the conditions that make it an audit are
# checked here instead of trusted:
#
#   1. every row satisfies  ported - upstream == added - removed
#   2. the three groups sum to the total, in all four columns
#   3. every file in COPY_SOURCES and TEST_SOURCES is byte-identical (removed == added == 0)
#   4. the file lists come from tools/sync-from-android.sh, so a file added to a list cannot be
#      silently missing from the table
#
# Exit 1 and say which condition failed. Reads upstream; writes nothing (AGENTS.md §1).
#
#   tools/ledger.sh            the table
#   tools/ledger.sh --check    the table, and exit 1 if it does not reconcile
set -uo pipefail

UPSTREAM="${UPSTREAM:-$(cd "$(dirname "$0")/../.." && pwd)/VoiceAssistant}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SYNC="$REPO_ROOT/tools/sync-from-android.sh"

UP="$UPSTREAM/app/src/main/java/com/nikosm/voiceassistant"
UP_TEST="$UPSTREAM/app/src/test/java/com/nikosm/voiceassistant"
CORE="$REPO_ROOT/core/src/main/kotlin/com/nikosm/voiceassistant"
CORE_TEST="$REPO_ROOT/core/src/test/kotlin/com/nikosm/voiceassistant"

# The lists are read out of the sync script rather than restated, so this can never disagree with
# what actually arrived. A PORT_SOURCES file is excluded on purpose: it is not a copy, and its port
# is one of the `changed` rows like any other.
# Each entry is "GROUP|name", and the group is stripped here — the first version kept it, which
# made every copy file an unreadable row AND left it out of the exclusion set below, so it was
# counted twice.
copy_main=$(sed -n '/^COPY_SOURCES=(/,/^)/p' "$SYNC" | sed -n 's/.*|\([^"]*\)".*/\1/p')
copy_test=$(sed -n '/^TEST_SOURCES=(/,/^)/p' "$SYNC" | sed -n 's/.*|\([^"]*\)".*/\1/p')

# `wc -l`: the number of newlines. It is the convention the table states, and it is why a file
# whose last line upstream does not terminate must not have one added here — see the note in
# docs/PROVENANCE.md. It is also the only count `diff` agrees with.
lines() { [ -f "$1" ] && wc -l < "$1" | tr -d ' ' || echo 0; }

# diff's own +/- counts. Every `-`-prefixed line except the single `--- <file>` header is a removed
# line, and the subtraction is exact for that reason. Written as `^-[^-]` first, which was wrong:
# it silently dropped removed lines whose content itself begins with a dash, and this port deleted
# a lot of `// ---- section ----` banners — 38 of them in AssistantServiceAudio.kt alone, which is
# how much of the arithmetic was off by.
counts() {
  local d r a
  d="$(diff -u "$1" "$2" 2>/dev/null)"
  # Empty output means the files are identical — which is the expected answer for every COPY file,
  # and returning "0 0" here is what makes it provable rather than assumed. Without this the
  # `-1` for the absent header line gave every byte-identical file a spurious `-1 / -1`.
  if [ -z "$d" ]; then printf '0 0'; return; fi
  r="$(printf '%s\n' "$d" | grep -c '^-' || true)"
  a="$(printf '%s\n' "$d" | grep -c '^+' || true)"
  printf '%s %s' "$((r-1))" "$((a-1))"
}

rows=(); sub=()

row() { # name upstream_file ported_file group
  local u p r a
  u="$(lines "$2")"; p="$(lines "$3")"
  read -r r a <<<"$(counts "$2" "$3")"
  rows+=("$4|$1|$u|$p|$r|$a")
}

for n in $copy_main; do row "$n" "$UP/$n" "$CORE/$n" copy-main; done
for n in $copy_test; do row "$n" "$UP_TEST/$n" "$CORE_TEST/$n" copy-test; done

# The `changed` group is every :core file with an upstream counterpart that is NOT on a copy list.
# The first version of this loop took all of them, which put the thirteen byte-identical files in
# both groups — the same double-count that made the hand-written table fail to reconcile, and the
# reason this is a script.
copy_names=" $(echo $copy_main $copy_test) "
for f in "$CORE"/*.kt; do
  n="$(basename "$f")"
  [ -f "$UP/$n" ] || continue
  case "$copy_names" in *" $n "*) continue ;; esac
  row "$n" "$UP/$n" "$f" changed
done

printf '%-34s%9s%9s%8s%7s%7s\n' "file" "upstream" "ported" "removed" "added" "net"
printf '%.0s-' {1..74}; echo

problems=0
tot=0; totu=0; totp=0; totr=0; tota=0
for g in changed copy-main copy-test; do
  gu=0; gp=0; gr=0; ga=0
  for e in "${rows[@]}"; do
    IFS='|' read -r eg en eu ep er ea <<<"$e"
    [ "$eg" = "$g" ] || continue
    printf '%-34s%9s%9s%8s%7s%+7s\n' "$en" "$eu" "$ep" "$er" "$ea" "$((ep-eu))"
    if [ $((ep-eu)) -ne $((ea-er)) ]; then
      echo "  ^ does not reconcile: ported-upstream=$((ep-eu)) but added-removed=$((ea-er))" >&2
      problems=$((problems+1))
    fi
    if [ "$g" != "changed" ] && { [ "$er" -ne 0 ] || [ "$ea" -ne 0 ]; }; then
      echo "  ^ $en is a COPY file and DIFFERS from upstream" >&2
      problems=$((problems+1))
    fi
    gu=$((gu+eu)); gp=$((gp+ep)); gr=$((gr+er)); ga=$((ga+ea)); tot=$((tot+1))
  done
  printf '%-34s%9s%9s%8s%7s%+7s\n' "$g subtotal" "$gu" "$gp" "$gr" "$ga" "$((gp-gu))"
  totu=$((totu+gu)); totp=$((totp+gp)); totr=$((totr+gr)); tota=$((tota+ga))
  sub+=("$g|$gu|$gp|$gr|$ga")
done

printf '%.0s-' {1..74}; echo
printf '%-34s%9s%9s%8s%7s%+7s\n' "TOTAL ($tot files)" "$totu" "$totp" "$totr" "$tota" "$((totp-totu))"

# Condition 2: the groups must sum to the total, in all four columns.
su=0; sp=0; sr=0; sa=0
for e in "${sub[@]}"; do IFS='|' read -r _ a b c d <<<"$e"; su=$((su+a)); sp=$((sp+b)); sr=$((sr+c)); sa=$((sa+d)); done
if [ "$su" -ne "$totu" ] || [ "$sp" -ne "$totp" ] || [ "$sr" -ne "$totr" ] || [ "$sa" -ne "$tota" ]; then
  echo "the groups do not sum to the total" >&2; problems=$((problems+1))
fi
# The whole-table identity, which is what the hand-typed version got wrong.
if [ $((totp-totu)) -ne $((tota-totr)) ]; then
  echo "the total does not reconcile: ported-upstream=$((totp-totu)) but added-removed=$((tota-totr))" >&2
  problems=$((problems+1))
fi

echo
if [ "$problems" -eq 0 ]; then
  echo "reconciles: $tot files, ported-upstream = added-removed = $((totp-totu))"
else
  echo "$problems problem(s) — this ledger does not reconcile" >&2
fi

[ "${1:-}" = "--check" ] && exit $((problems > 0))
exit 0
