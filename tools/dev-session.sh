#!/usr/bin/env bash
#
# dev-session.sh — run a Celeste-port coding session on buildhost, with the machine freed for it.
#
# Order of operations, and why:
#   1. find the repo                      — fail before touching the machine if anything is wrong
#   2. get the DeepSeek key               — from the environment, or 0600 keyfile, or ask once
#   3. verify the key, resolve the model  — a wrong key or a guessed model id must fail HERE,
#                                           not after the agent has spent tokens (and never v4-pro), and report whether the tariff is peak
#                                           or off-peak right now, since peak doubles the rate
#   4. ask for your sudo password ONCE    — sudo -v up front, so nothing stops halfway through
#   5. free the machine                   — stop comfyui, unload ollama models (mirrors ~/bin/dev-mode)
#   6. launch the harness in the repo
#   7. on exit, offer to start comfyui again
#
# About ComfyUI: stopping it by hand is SAFE here. /usr/local/sbin/comfyui-gate.sh states it
# outright — a stop the gate did not make is left alone — and /etc/default/comfyui-gate sets
# MAX_PROMPTS=0, so the idle-restart path is off too. A service you (or this script) stopped
# stays stopped until someone starts it.
#
# Usage:
#   tools/dev-session.sh                 full session
#   tools/dev-session.sh --check         show key status, model ids, balance, harness. Changes nothing.
#   tools/dev-session.sh --gate-only     free the machine and exit (still offers to restore)
#   tools/dev-session.sh --no-restore    leave comfyui stopped when the session ends
#   tools/dev-session.sh --force         continue even if comfyui will not stop
#   tools/dev-session.sh --harness NAME  opencode | claude | /path/to/binary   (default: auto)
#
# Environment:
#   DEEPSEEK_API_KEY    key, if you keep it in the environment instead of a file
#   DEEPSEEK_KEY_FILE   override the keyfile path (default ~/.config/celeste-linux/deepseek.key)
#   REPO                override the repo path   (default $HOME/VoiceAssistant-Linux)
#
# Written for buildhost by the ServerBox agent, 2026-10-15. Mirrors the user's ~/bin/dev-mode.

set -Eeuo pipefail

REPO="${REPO:-$HOME/VoiceAssistant-Linux}"
KEYFILE="${DEEPSEEK_KEY_FILE:-$HOME/.config/celeste-linux/deepseek.key}"
API_BASE="https://api.deepseek.com"
UPSTREAM="${UPSTREAM:-../VoiceAssistant}"
USAGE_URL="https://platform.deepseek.com/usage"
COMFY_SERVICE="comfyui"

MODE="session"
NO_RESTORE=0
FORCE=0
HARNESS="auto"

while [ $# -gt 0 ]; do
  case "$1" in
    --check)      MODE="check" ;;
    --gate-only)  MODE="gate" ;;
    --no-restore) NO_RESTORE=1 ;;
    --force)      FORCE=1 ;;
    --harness)    HARNESS="${2:-}"; shift ;;
    -h|--help)    sed -n '2,36p' "$0"; exit 0 ;;
    *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

# ---------------------------------------------------------------- presentation
if [ -t 1 ]; then
  B=$'\033[1m'; DIM=$'\033[2m'; R=$'\033[0m'; GRN=$'\033[32m'; YEL=$'\033[33m'; RED=$'\033[31m'
else
  B=""; DIM=""; R=""; GRN=""; YEL=""; RED=""
fi
say()  { printf '%s\n' "$*"; }
hdr()  { printf '\n%s== %s%s\n' "$B" "$*" "$R"; }
ok()   { printf '%s  ok%s   %s\n' "$GRN" "$R" "$*"; }
warn() { printf '%s  warn%s %s\n' "$YEL" "$R" "$*"; }
die()  { printf '\n%s  stop%s %s\n' "$RED" "$R" "$*" >&2; exit 1; }

# The key must never reach a command line (visible in /proc/*/cmdline), so every request goes
# through a 0600 curl config file that carries the header instead.
TMPDIR_SESSION="$(mktemp -d)"
cleanup_tmp() { rm -rf "$TMPDIR_SESSION"; }
trap cleanup_tmp EXIT
BODYFILE="$TMPDIR_SESSION/body"

CURLE_CFG=""
deepseek_get() { # $1 = path. sets CODE and BODY
  CURLE_CFG="$TMPDIR_SESSION/curl.cfg"
  ( umask 077; printf 'silent\nshow-error\nmax-time = 30\nheader = "Authorization: Bearer %s"\n' "$KEY" > "$CURLE_CFG" )
  CODE="$(curl -K "$CURLE_CFG" -o "$BODYFILE" -w '%{http_code}' "$API_BASE$1" 2>"$TMPDIR_SESSION/curlerr" || echo 000)"
  BODY="$(cat "$BODYFILE" 2>/dev/null || true)"
  rm -f "$CURLE_CFG"
}

# Peak is 01:00-04:00 and 06:00-10:00 UTC, Monday to Friday; everything else - all weekend and Chinese
# public holidays - is off-peak at half price (DeepSeek's published rule). Computed rather than stated,
# so the line is still true at 08:59, and the local windows come from this machine's current offset.
# TARIFF_UTC_EPOCH takes a UTC epoch and exists so both branches can be demonstrated with a fixed clock.
tariff_report() {
  local utc_s off dow mins
  utc_s="${TARIFF_UTC_EPOCH:-$(date -u +%s)}"
  off=$(( ( $(date +%s) - $(date -u +%s) ) / 3600 ))
  dow=$(date -u -d "@$utc_s" +%u)
  mins=$(( 10#$(date -u -d "@$utc_s" +%H) * 60 + 10#$(date -u -d "@$utc_s" +%M) ))

  local windows
  windows=$(printf '%02d:00-%02d:00 and %02d:00-%02d:00' \
    $(( (1 + off) % 24 )) $(( (4 + off) % 24 )) $(( (6 + off) % 24 )) $(( (10 + off) % 24 )))

  if [ "$dow" -le 5 ] && { { [ "$mins" -ge 60 ] && [ "$mins" -lt 240 ]; } || { [ "$mins" -ge 360 ] && [ "$mins" -lt 600 ]; }; }; then
    local end_ts left
    if [ "$mins" -lt 240 ]; then
      end_ts=$(TZ=UTC date -d "$(date -u -d "@$utc_s" +%F) 04:00" +%s)
    else
      end_ts=$(TZ=UTC date -d "$(date -u -d "@$utc_s" +%F) 10:00" +%s)
    fi
    left=$(( (end_ts - utc_s) / 60 ))
    warn "tariff: PEAK — double rate until $(date -d "@$end_ts" '+%H:%M') local (${left}m from now)"
    say "${DIM}  off-peak halves it: 0.15 instead of 0.30 per M cache-miss input, 0.60 instead of 1.20 per M output${R}"
  else
    local next_ts="" ts d off_d start day
    for off_d in 0 1 2 3 4; do
      day=$(date -u -d "@$(( utc_s + off_d * 86400 ))" +%F)
      for start in 01:00 06:00; do
        ts=$(TZ=UTC date -d "$day $start" +%s)
        d=$(date -u -d "@$ts" +%u)
        [ "$d" -le 5 ] || continue
        if [ "$ts" -gt "$utc_s" ]; then next_ts="$ts"; break 2; fi
      done
    done
    ok "tariff: off-peak (half price) — peak today is $windows local"
    if [ -n "$next_ts" ]; then
      local delta=$(( (next_ts - utc_s) / 60 ))
      say "${DIM}  next peak: $(date -d "@$next_ts" '+%a %H:%M') local, in $(( delta / 60 ))h $(( delta % 60 ))m${R}"
    fi
  fi
}

# ---------------------------------------------------------------- 1. repo
hdr "repo"
[ -d "$REPO" ] || die "repo not found: $REPO (set REPO=...)"
[ -f "$REPO/AGENTS.md" ] || die "$REPO does not look like the port repo (no AGENTS.md)"
cd "$REPO"
UPSTREAM_REV="$(git -C "$UPSTREAM" rev-parse --short HEAD 2>/dev/null || echo unknown)"
BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '-')"
DIRTY="$(git status --porcelain | wc -l)"
ok "$REPO (branch $BRANCH, pinned upstream $UPSTREAM_REV)"
[ "$DIRTY" -gt 0 ] && warn "$DIRTY uncommitted path(s) — the agent should commit before starting" || ok "working tree clean"

# ---------------------------------------------------------------- 2. key
hdr "DeepSeek key"
if [ -n "${DEEPSEEK_API_KEY:-}" ]; then
  KEY="$DEEPSEEK_API_KEY"; KEY_SRC="environment"
elif [ -r "$KEYFILE" ]; then
  KEY="$(tr -d '\r\n' < "$KEYFILE")"; KEY_SRC="$KEYFILE"
elif [ "$MODE" = "check" ]; then
  die "no key found: set DEEPSEEK_API_KEY or create $KEYFILE (chmod 600)"
else
  [ -e /dev/tty ] || die "no key: set DEEPSEEK_API_KEY or create $KEYFILE (chmod 600)"
  printf 'DeepSeek API key (hidden, from https://platform.deepseek.com/api_keys): ' > /dev/tty
  IFS= read -rs KEY < /dev/tty; printf '\n' > /dev/tty
  [ -n "$KEY" ] || die "no key entered"
  KEY_SRC="entered now"
  printf 'Save it to %s (mode 600) so you are not asked again? [Y/n] ' "$KEYFILE" > /dev/tty
  IFS= read -r ans < /dev/tty || ans=""
  case "${ans:-y}" in
    [Yy]*) mkdir -p "$(dirname "$KEYFILE")"; ( umask 077; printf '%s\n' "$KEY" > "$KEYFILE" ); chmod 600 "$KEYFILE"
           ok "saved to $KEYFILE (600)" ;;
    *)     warn "not saved" ;;
  esac
fi

# Strip a pasted "Bearer " prefix — a common copy/paste accident that produces a 401.
KEY="${KEY#Bearer }"
KEY="${KEY#"${KEY%%[![:space:]]*}"}"
[ -n "$KEY" ] || die "key is empty"

KEY_SHOWN="***${KEY: -4}"
ok "key $KEY_SHOWN from $KEY_SRC (${#KEY} chars)"

# ---------------------------------------------------------------- 3. verify + resolve models
hdr "DeepSeek"
deepseek_get "/models"
case "$CODE" in
  200) ok "key accepted" ;;
  401|403) die "DeepSeek rejected the key (HTTP $CODE). Check it at platform.deepseek.com/api_keys" ;;
  000) die "could not reach $API_BASE (network?). curl said: $(tr -d '\n' < "$TMPDIR_SESSION/curlerr" 2>/dev/null)" ;;
  *)   warn "GET /models returned HTTP $CODE; continuing with default model names" ;;
esac

MODEL_FLASH="deepseek-flash"
MODEL_PRO=""
if [ "$CODE" = "200" ] && command -v jq >/dev/null 2>&1; then
  IDS="$(printf '%s' "$BODY" | jq -r '(.data // [])[]?.id' 2>/dev/null || true)"
  FOUND_FLASH="$(printf '%s\n' "$IDS" | grep -i 'flash' | head -1 || true)"
  FOUND_PRO="$(printf '%s\n' "$IDS" | grep -iE 'pro' | head -1 || true)"
  [ -n "$FOUND_FLASH" ] && { [ "$FOUND_FLASH" != "$MODEL_FLASH" ] && warn "model id differs from the documented name"; MODEL_FLASH="$FOUND_FLASH"; }
  MODEL_PRO="$FOUND_PRO"
  ok "flash model: $MODEL_FLASH"
  [ -n "$MODEL_PRO" ] && ok "pro model (will be blacklisted): $MODEL_PRO" || warn "no 'pro' id listed; blacklist it yourself once it appears"
else
  warn "could not list models; assuming '$MODEL_FLASH'"
fi

deepseek_get "/user/balance"
if [ "$CODE" = "200" ] && command -v jq >/dev/null 2>&1; then
  BAL="$(printf '%s' "$BODY" | jq -r '(.balance_infos // [])[0] | "\(.total_balance // "?") \(.currency // "")"' 2>/dev/null || true)"
  [ -n "$BAL" ] && ok "balance: $BAL" || warn "balance: could not read the response"
  tariff_report
  say "${DIM}  watch the spend at $USAGE_URL — check it after every milestone.${R}"
else
  warn "balance check unavailable (HTTP $CODE)"
fi

# ---------------------------------------------------------------- harness
hdr "harness"
export PATH="$HOME/.local/bin:$PATH"     # where claude actually lives on this box
case "$HARNESS" in
  auto)
    if   command -v opencode >/dev/null 2>&1; then HARNESS="opencode"
    elif command -v claude   >/dev/null 2>&1; then HARNESS="claude"
    else HARNESS=""; fi ;;
esac
case "$HARNESS" in
  "") die "no harness found: install opencode (direct DeepSeek API, preferred) or Claude Code, or pass --harness /path/to/binary" ;;
  opencode|claude) command -v "$HARNESS" >/dev/null 2>&1 || die "$HARNESS not on PATH" ;;
  *) [ -x "$HARNESS" ] || die "not executable: $HARNESS" ;;
esac
ok "harness: $HARNESS ($(command -v "$HARNESS" 2>/dev/null || echo "$HARNESS"))"

# "flash[1m]" is the form DeepSeek's own Claude Code page uses for the long-context variant.
# Only Claude Code wants the suffix; the plain id is what the direct API takes.
CC_MODEL="${MODEL_FLASH}[1m]"
if [ "$HARNESS" = "claude" ]; then
  export ANTHROPIC_BASE_URL="$API_BASE/anthropic"
  export ANTHROPIC_AUTH_TOKEN="$KEY"
  export ANTHROPIC_MODEL="$CC_MODEL"
  export ANTHROPIC_DEFAULT_OPUS_MODEL="$CC_MODEL"
  export ANTHROPIC_DEFAULT_SONNET_MODEL="$CC_MODEL"
  export ANTHROPIC_DEFAULT_HAIKU_MODEL="$MODEL_FLASH"
  export CLAUDE_CODE_SUBAGENT_MODEL="$MODEL_FLASH"
  export CLAUDE_CODE_EFFORT_LEVEL=max
  export CLAUDE_CODE_AUTO_COMPACT_WINDOW=786432
  ok "Claude Code pointed at DeepSeek's Anthropic-format endpoint"
  warn "every model slot is pinned to flash on purpose: the endpoint maps claude-opus* to v4-pro (~5x the price)"
  warn "do not switch the session model with /model — leaving the flash slots is what keeps the budget"
else
  export DEEPSEEK_API_KEY="$KEY"
  ok "DEEPSEEK_API_KEY exported for opencode (direct DeepSeek API, no shim)"
  if [ ! -f "$REPO/opencode.json" ] && [ -n "$MODEL_PRO" ]; then
    cat > "$REPO/opencode.json" <<JSON
{
  "\$schema": "https://opencode.ai/config.json",
  "model": "deepseek/$MODEL_FLASH",
  "provider": { "deepseek": { "blacklist": ["$MODEL_PRO"] } }
}
JSON
    ok "created opencode.json pinning $MODEL_FLASH and blacklisting $MODEL_PRO"
    warn "it is untracked by git on purpose-gitignore it, or commit it if you want it shared"
  elif [ -f "$REPO/opencode.json" ]; then
    grep -q "$MODEL_FLASH" "$REPO/opencode.json" \
      && ok "existing opencode.json already pins $MODEL_FLASH" \
      || warn "opencode.json exists but does not mention $MODEL_FLASH — check it before letting the agent run"
  fi
fi

# ---------------------------------------------------------------- dry run stops here
if [ "$MODE" = "check" ]; then
  hdr "--check"
  say "nothing was stopped, nothing was changed. Drop --check to run a session."
  exit 0
fi

# ---------------------------------------------------------------- 4. sudo, asked once, up front
hdr "sudo"
[ -e /dev/tty ] || [ "$(id -u)" -eq 0 ] || die "no terminal for the sudo prompt; run this interactively"
say "asking for your password now, once, before anything is stopped or started."
sudo -v < /dev/tty || die "sudo refused — nothing stopped, agent not started"
ok "sudo credentials accepted"

# ---------------------------------------------------------------- 5. free the machine
hdr "memory gate"
STOPPED_BY_US=0
AVAIL_BEFORE="$(free -m | awk '/^Mem:/{print $7}')"
say "before: ${AVAIL_BEFORE} MiB available, ${DIM}$(systemctl is-active "$COMFY_SERVICE" 2>&1)${R} $COMFY_SERVICE"

if systemctl is-active --quiet "$COMFY_SERVICE"; then
  say "stopping $COMFY_SERVICE (a stop you make stays a stop — comfyui-gate will not undo it)"
  sudo systemctl stop "$COMFY_SERVICE" < /dev/tty || warn "systemctl stop returned non-zero"
  for _ in $(seq 1 20); do systemctl is-active --quiet "$COMFY_SERVICE" || break; sleep 1; done
  if systemctl is-active --quiet "$COMFY_SERVICE"; then
    [ "$FORCE" = 1 ] || die "$COMFY_SERVICE is still active — refusing to start the agent into a possible OOM (--force to override)"
    warn "$COMFY_SERVICE still active; continuing because --force"
  else
    STOPPED_BY_US=1
    ok "$COMFY_SERVICE stopped"
  fi
else
  ok "$COMFY_SERVICE was already stopped — leaving it exactly as you set it"
fi

# Best effort, no sudo needed: ollama reloads a model on demand, so this is free.
if command -v ollama >/dev/null 2>&1; then
  MODELS="$(ollama ps 2>/dev/null | awk 'NR>1 {print $1}' || true)"
  if [ -n "$MODELS" ]; then
    printf '%s\n' "$MODELS" | xargs -r -n1 ollama stop >/dev/null 2>&1 || true
    ok "unloaded ollama model(s): $(printf '%s' "$MODELS" | tr '\n' ' ')"
  else
    ok "no ollama models resident"
  fi
fi

# Re-check just before launching: if the gate or anything else brought ComfyUI back, say so.
sleep 1
if systemctl is-active --quiet "$COMFY_SERVICE"; then
  warn "$COMFY_SERVICE came back (something restarted it) — stopping it once more"
  sudo systemctl stop "$COMFY_SERVICE" < /dev/tty || true
  STOPPED_BY_US=1
fi
AVAIL_AFTER="$(free -m | awk '/^Mem:/{print $7}')"
ok "after: ${AVAIL_AFTER} MiB available (freed $(( AVAIL_AFTER - AVAIL_BEFORE )) MiB)"

# ---------------------------------------------------------------- 6. the session
hdr "session"
say "repo    : $REPO"
say "harness : $HARNESS, model $MODEL_FLASH"
say "start by pasting the prompt in ${B}docs/BOOTSTRAP.md §6${R} — M0 only, six steps, one commit each."
say "the contract is AGENTS.md; the specification is PLAN.md."
say "${DIM}Burn less by not re-exploring the tree: the file map is PLAN.md §7.${R}"
say ""

sudo -v < /dev/tty || true      # refresh the timestamp so the exit path is unlikely to prompt
AGENT_RC=0
"$HARNESS" || AGENT_RC=$?

# ---------------------------------------------------------------- 7. put it back, if wanted
hdr "restore"
if [ "$STOPPED_BY_US" = 0 ]; then
  ok "this script stopped nothing, so there is nothing to restore"
elif [ "$NO_RESTORE" = 1 ]; then
  warn "leaving $COMFY_SERVICE stopped (--no-restore). Start it with: sudo systemctl start $COMFY_SERVICE"
elif [ -e /dev/tty ]; then
  printf 'The session is over. Start %s again now? [Y/n] ' "$COMFY_SERVICE" > /dev/tty
  IFS= read -r ans < /dev/tty || ans="y"
  case "${ans:-y}" in
    [Nn]*) warn "left stopped. Start it with: sudo systemctl start $COMFY_SERVICE" ;;
    *)     if sudo systemctl start "$COMFY_SERVICE" < /dev/tty; then ok "$COMFY_SERVICE started"; else warn "start failed — run: sudo systemctl start $COMFY_SERVICE"; fi ;;
  esac
else
  if sudo -n systemctl start "$COMFY_SERVICE" 2>/dev/null; then ok "$COMFY_SERVICE started"; else warn "no terminal to prompt for sudo; run: sudo systemctl start $COMFY_SERVICE"; fi
fi

hdr "done"
say "agent exit code: $AGENT_RC   (uncommitted paths: $(git -C "$REPO" status --porcelain | wc -l))"
say "spend so far: $USAGE_URL"
exit "$AGENT_RC"
