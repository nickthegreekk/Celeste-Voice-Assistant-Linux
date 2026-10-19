#!/usr/bin/env bash
# network-audit.sh — every URL literal in :core, checked against the recorded table.
#
# AGENTS.md §5: "No network call that the user did not configure, except the build's own dependency
# resolution." That rule is enforced by grepping rather than by remembering, because it was being
# read as a list of dropped features — and then a fresh install made a real request to openrouter.ai,
# and a second call of the same shape turned out to be hiding behind a seeded provider template.
#
# The table in PLAN.md §5 is the record: twenty-one URLs, each with what makes it safe. This is what
# keeps the record honest, because a table nobody re-derives becomes a table describing last year.
#
# It fails on a URL that is not in the table. **That is the whole point** — a new fetch target is the
# thing this project keeps getting wrong, and a script that only listed URLs would let one in as
# easily as a script that did nothing. The check is deliberately dumb: it compares string sets, so it
# cannot be talked out of a finding. Deciding whether the new URL is safe is a human's job, and the
# fix is to add it to the table *with its reason*.
#
#   tools/network-audit.sh            the table, and exit 1 if anything is unrecorded
#   tools/network-audit.sh --list     just the URLs, for a diff
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$REPO_ROOT/core/src/main/kotlin/com/nikosm/voiceassistant"
PLAN="$REPO_ROOT/PLAN.md"

found="$(grep -rhoE 'https?://[a-zA-Z0-9.:/_-]+' "$CORE"/*.kt | sort -u)"
# The table's own cells, so the two sets are compared as written rather than as parsed.
recorded="$(sed -n '/^| URL | Where | What makes it safe |$/,/^$/p' "$PLAN" \
  | grep -oE 'https?://[a-zA-Z0-9.:/_-]+' | sort -u)"

if [ "${1:-}" = "--list" ]; then
  printf '%s\n' "$found"
  exit 0
fi

problems=0
unrecorded="$(comm -23 <(printf '%s\n' "$found") <(printf '%s\n' "$recorded"))"
if [ -n "$unrecorded" ]; then
  echo "NOT IN THE TABLE — each needs a row in PLAN.md §5 saying what makes it safe:" >&2
  printf '  %s\n' $unrecorded >&2
  problems=$((problems + 1))
fi

stale="$(comm -13 <(printf '%s\n' "$found") <(printf '%s\n' "$recorded"))"
if [ -n "$stale" ]; then
  echo "IN THE TABLE but no longer in :core — the row is describing a URL that moved or went away:" >&2
  printf '  %s\n' $stale >&2
  problems=$((problems + 1))
fi

echo "URLs in :core        $(printf '%s\n' "$found" | wc -l | tr -d ' ')"
echo "recorded in PLAN §5  $(printf '%s\n' "$recorded" | wc -l | tr -d ' ')"
if [ "$problems" -eq 0 ]; then
  echo "every URL literal is accounted for"
  echo
  echo "The check is that each one is *recorded*, not that it is safe. Safety is the table's"
  echo "third column, and a URL whose reason is \"it fires on its own but it is only loopback\" is"
  echo "the carve-out AGENTS.md §5 rejects on purpose: a rule with one stops being greppable."
  exit 0
fi
echo
echo "$problems problem(s) — the table and the tree disagree" >&2
exit 1
