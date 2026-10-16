#!/usr/bin/env bash
#
# tariff-selftest.sh — check the tariff line's branch and arithmetic against a fixed clock.
#
# The tariff lives in tools/dev-session.sh, where it is computed from the clock rather than stated in a
# comment. That has two failure modes worth a test: reading the wrong branch near a boundary, and getting
# the local-time rendering wrong. Both are checked here against fixed UTC instants, and the expected local
# times are derived from this machine's current offset — so the test keeps passing when Athens changes
# offset on 26 October, which is the same property the function claims.
#
# tarrioff_report is read out of dev-session.sh by extraction: that script runs as a program, so it cannot be
# sourced. The extraction is checked before use, so a refactor that breaks it fails loudly instead of
# quietly testing nothing.

set -u
cd "$(dirname "$0")/.."

FN=/tmp/tariff-fn.$$.sh
sed -n '/^tariff_report()/,/^}/p' tools/dev-session.sh > "$FN"
if ! grep -q '^tariff_report()' "$FN"; then
  echo "could not extract tariff_report from tools/dev-session.sh" >&2
  rm -f "$FN"; exit 1
fi
trap 'rm -f "$FN"' EXIT

harness() {
  bash -c '
    say(){ printf "%s\n" "$*"; }; ok(){ printf "%s\n" "$*"; }; warn(){ printf "%s\n" "$*"; }
    DIM=""; R=""
    . '"$FN"'
    tariff_report'
}

z=$(date +%z)
sign=1; [ "${z:0:1}" = "-" ] && sign=-1
off=$(( sign * 10#${z:1:2} ))
first_end=$(printf '%02d:00' $(( (4  + off) % 24 )))
second_end=$(printf '%02d:00' $(( (10 + off) % 24 )))
monday=$(printf '%02d:00' $(( (1  + off) % 24 )))

pass=0; fail=0
check() {  # label, fixed UTC instant (empty = the live clock), expected regex
  local label="$1" iso="$2" want="$3" out ep
  if [ -z "$iso" ]; then
    out=$(harness)
  else
    ep=$(python3 -c "import datetime as d,sys;print(int(d.datetime.strptime(sys.argv[1],'%Y-%m-%d %H:%M').replace(tzinfo=d.timezone.utc).timestamp()))" "$iso")
    out=$(TARIFF_UTC_EPOCH=$ep harness)
  fi
  if printf '%s' "$out" | grep -qE "$want"; then
    printf 'ok    %s\n' "$label"; pass=$((pass + 1))
  else
    printf 'FAIL  %s\n        wanted: %s\n        got:    %s\n' "$label" "$want" "$(printf '%s' "$out" | tr '\n' '|')"
    fail=$((fail + 1))
  fi
}

check "Friday inside the first window is peak, ending $first_end local" "2026-10-16 03:00" "tariff: PEAK.*until $first_end local"
check "Friday inside the second window is peak, ending $second_end local" "2026-10-16 07:00" "tariff: PEAK.*until $second_end local"
check "Friday after the second window is off-peak, next peak Monday $monday local" "2026-10-16 11:00" "off-peak.*Mon $monday local"
check "Saturday is off-peak, next peak Monday $monday local" "2026-10-17 12:00" "off-peak.*Mon $monday local"
check "Sunday evening is off-peak, next peak Monday $monday local" "2026-10-18 20:00" "off-peak.*Mon $monday local"
check "the live clock reports one of the two tariffs" "" 'tariff: (off-peak|PEAK)'

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
