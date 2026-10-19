# Celeste 0.2.0

**Release date:** 2026-10-19
**Platforms:** Arch Linux (x86_64), Debian 13 “trixie” (amd64)
**Upstream base:** `VoiceAssistant` @ `fe584ad0` (unchanged)

This is a bug-fix update to 0.1.0, plus one small feature: a manual update check in About.
No dependency changes and no data migration. Existing settings, personas, chats, memories and
backups are compatible.

## New

- **Manual update check in About.** A **Check for updates** button queries GitHub’s latest
  release and reports whether a newer version exists. It runs only when pressed, sends no user
  data beyond the application version, and never downloads or installs anything. The repository
  and releases links are shown beside it; the releases page remains the update path. The
  automatic check stays dropped.

## Fixes

### Filesystem and tools

- **Hard-linked files are refused** by the workspace gate, for reads and writes. A hard link
  inside the workspace to an inode also named outside it passed every path check, because
  `toRealPath` resolves the inside name to itself and the bytes are the outside inode. The
  gate now refuses any regular file with `unix:nlink > 1` and says why. This is a heuristic —
  it also refuses legitimate hard-linked files — and a workspace on its own filesystem is the
  structural fix; both facts are recorded in `docs/DECISIONS.md`.
- The lexical `..` collapse before symlink resolution is now pinned by a test, so
  `link-out/../notes.md` cannot become an escape through a refactor.
- Percent-encoded traversals (`%2e%2e%2f`) are treated as literal file names, never decoded;
  NUL bytes are explicitly refused.
- Approval timeouts now say the action **was not approved and nothing ran**, instead of
  telling the user a question is still waiting after the gate has withdrawn it. The MCP
  timeout notice now uses the desktop notification channel, not a snackbar in a window that
  may not exist.

### Stability

- **Spontaneous deliveries keep their re-armed successor.** Editing a schedule after the
  first delivery could leave the successor untracked and start a second chain, producing
  duplicate spontaneous messages. The timer now remembers the job inside the delivery and
  only removes its own slot.
- **Every helper-process read has a real deadline.** Local speech synthesis, `notify-send`,
  the audio device probes (`pactl`/`wpctl`), the player probes (`pw-play`/`paplay`/`aplay`)
  and the system font probe (`fc-match`) now drain their pipes beside a bounded wait and kill
  a wedged child at the deadline. A stuck helper can no longer hang a turn, the device picker
  or the launch.
- **MCP transports survive a bad server.** A stdio `close` can destroy a server that stopped
  reading its stdin instead of waiting forever behind a blocked `send`; one slow or timed-out
  HTTP request no longer closes the transport for every other pending request; a server error
  marks the transport closed so a subsequent send is refused; send/close races and a rotated
  `Mcp-Session-Id` are handled. Stopped turns now cancel cleanly instead of being answered
  “no tools” or “could not run”.
- Same-session float settings read back correctly instead of returning their default until
  the next restart.
- `PwPlayback` escalates to a forced kill after its polite grace and serializes starts, so a
  player that ignores SIGTERM cannot overlap the next clip.

### Desktop and UI

- `--send-text` / `--send-file` payloads land in the visible chat. The first-run welcome
  screen keeps them pending until it is dismissed, and a share arriving over a room or the
  HUD opens text mode and leaves the room before landing.
- Persona names from a backup can no longer inject `.desktop` keys: line breaks are flattened
  in every value, including the artwork path.
- Dead update-check declarations and the unreachable update banner were removed.

## Security

- Settings recovery no longer logs the settings file’s contents. The failure path used to
  interpolate the whole file — provider keys and the RAG password — into an ERROR line.
- Hard-linked files inside a workspace can no longer expose content outside it.
- Desktop-entry generation is no longer an injection point for persona names.
- MCP transport lifetime and cancellation no longer fail other pending calls or swallow a
  stopped turn.

## Packages

| Package | Size | SHA-256 |
|---|---:|---|
| `celeste-linux-0.2.0-1-x86_64.pkg.tar.zst` (Arch) | 112,205,274 bytes | `25f8ba7bd8b312ad317c07c9a9e9a146bd0d6b5dfc6f6ccee579007104d0746f` |
| `celeste-linux_0.2.0_amd64.deb` (Debian) | 100,393,608 bytes | `011467915414e83dbbfa9bf52d93c625c503a2c061aabfba3a2467327ab503a5` |
| `celeste-linux-dbgsym_0.2.0_amd64.deb` (debug symbols) | 1,452,432 bytes | `6e6f6e48a86bc4b194b7f1e13a9d7019795fe533dacccfec4b7f0e5f235a674d` |

Install:

```sh
# Arch
sudo pacman -U celeste-linux-0.2.0-1-x86_64.pkg.tar.zst

# Debian
sudo apt install ./celeste-linux_0.2.0_amd64.deb
```

## Verification

- `./gradlew :core:test :platform:test` — green.
- `./gradlew build` — green.
- `tools/network-audit.sh` — every URL literal is recorded in PLAN.md §5 (21 URLs), including
  the manual update check's GitHub endpoint and the repository link.
- Arch package built with `makepkg -f`; the packaging `check()` loaded the trimmed ONNX
  runtime and reported `onnx native loaded — session inputs: [input, state, sr]`.
- Debian package built on Debian 13 in the project’s container environment; control version
  is `0.2.0`, and the ONNX jar is the trimmed 5,959,512-byte one.
- Both packages were checked for the update-check classes (`UpdateCheckKt`,
  `UpdateCheckUiKt`) inside their `core-0.2.0-*.jar`.
- `tools/sync-from-android.sh --check` — no drift; upstream `git status` clean.
- **Live acceptance:** pending. This file is the release body for that run; record the result
  before publishing.

## Known limitations

- MCP still uses the M9 timeouts: a 30 s HTTP read deadline and a 35 s registry call deadline.
  A tool slower than that fails that call; the transport now survives it and other requests
  continue. Raising the timeout is a separate design decision.
- `PwPlayback` can report `Finished` instead of `Stopped` if the player exits in the same
  instant `stop()` is called. The state difference is benign.
- The hard-link refusal is a heuristic, as above; legitimate hard-linked files in a workspace
  are refused too.
- Echo cancellation is still absent. Use headphones or a PipeWire `module-echo-cancel` sink.

## Upgrading from 0.1.0

No migration is required. The packages replace the application image; settings, personas,
chats, memories and images stay under the existing XDG paths. Backups from 0.1.0 import
unchanged. Filesystem/MCP trust and workspace paths are device-local and are deliberately not
restored from a backup.

The full commit range for this release is `d2b2e22..4a14691`.
