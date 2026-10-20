# Celeste 0.2.1

**Release date:** 2026-10-20
**Platforms:** Arch Linux (x86_64), Debian 13 “trixie” (amd64)
**Upstream base:** `VoiceAssistant` @ `fe584ad0` (unchanged)

This is a bug-fix update to 0.2.0, plus one small feature: Markdown conversation export.
No dependency changes and no data migration. Existing settings, personas, chats, memories and
backups are compatible.

## Fixes

### Hands-free listening and the microphone

- **A lost microphone stops hands-free and says so.** The capture loop already stopped itself
  when the stream ended — `pw-record` exited, the device was unplugged, or the sound server
  restarted — but only the loop knew. The service kept `handsFreeMode` true, the tray and status
  line said “listening”, and nothing recovered or explained. A transient hardware event left the
  feature silently dead until it was toggled by hand. The recorder now reports the loss once; the
  service switches hands-free off, releases the recorder, restores IDLE and posts a notification
  (“Hands-free listening stopped — …”). A microphone that cannot be opened at the start of a
  session is reported the same way, because the mode used to be switched on over a recorder that
  had opened nothing.
- **A negative capture read is end-of-stream too.** `CaptureStream.read`’s contract allowed a
  negative return for a stream that ended, while the loop only handled 0; an implementation using
  the documented form would have reproduced the per-iteration hot loop the 0 check exists to
  prevent. Both are now the end.

No automatic reopen is attempted: a missing device is not retried into a hot loop, a returning
USB microphone may be a different PipeWire node, and starting again is one press — which is also
when a changed device choice applies. The reasoning is recorded in `docs/DECISIONS.md`.

## New

- **Export a conversation to Markdown.** The chat menu has **Export chat…**, and the room header
  has the same action. The document is one Markdown file with speaker headings, timestamps, error
  blockquotes and image references; images are copied into a sibling `<export>.images/` folder and
  linked relatively so the export survives being moved or emailed. A missing attachment is named
  in the document rather than failing the export. Model reasoning is omitted, and legacy messages
  with an unknown timestamp render no invented time. The write runs on the service scope, so
  closing the window cannot truncate it.

## Packages

| Package | Size | SHA-256 |
|---|---:|---|
| `celeste-linux-0.2.1-1-x86_64.pkg.tar.zst` (Arch) | 112,254,333 bytes | `b80e93677334bd3e6a2da67d87c395dd41d4990bbe72cc69ee7d7abf82be8cd3` |
| `celeste-linux_0.2.1_amd64.deb` (Debian) | 100,411,216 bytes | `e700b1e4c3c3bf04250aa224dcf2d5c8d03a4edce2d542d0dbf9708ab7435c22` |
| `celeste-linux-dbgsym_0.2.1_amd64.deb` (debug symbols) | 1,450,196 bytes | `8248a248dc09bc65f0798102e0d73de7d3b49dc7ad0556bf8768830f672deb47` |

Install:

```sh
# Arch
sudo pacman -U celeste-linux-0.2.1-1-x86_64.pkg.tar.zst

# Debian
sudo apt install ./celeste-linux_0.2.1_amd64.deb
```

## Verification

- `./gradlew :core:test :platform:test build` — green.
- `tools/network-audit.sh` — every URL literal is recorded in PLAN.md §5 (21 URLs); no new
  network call was added.
- Arch package built with `makepkg -f`; the packaging `check()` loaded the trimmed ONNX runtime
  and reported `onnx native loaded — session inputs: [input, state, sr]`.
- Debian package built on Debian 13 in the project’s container environment; control version is
  `0.2.1`.
- Both packages were extracted and inspected: each carries `core-0.2.1-*.jar`, the export classes
  (`ConversationExportKt`) and the recorder’s death report (`VADAudioRecorder$reportDeath`), the
  application version reads `0.2.1` (About and the MCP client info), and the ONNX jar is trimmed
  to `linux-x64` alone.
- `tools/sync-from-android.sh --check` — no drift; upstream pin unchanged.
- **Live acceptance:** pending. This file is the release body for that run; record the result
  before publishing.

## Known limitations

- Echo cancellation is still absent. Use headphones or a PipeWire `module-echo-cancel` sink.
- Markdown export omits model reasoning and writes English speaker labels (“You”); the message
  text itself is exported verbatim, including the user’s own Markdown.
- The other 0.2.0 limitations are unchanged: the MCP timeouts, the benign `PwPlayback`
  Finished/Stopped race, and the hard-link refusal heuristic.

## Upgrading from 0.2.0

No migration is required. The packages replace the application image; settings, personas,
chats, memories and images stay under the existing XDG paths. Backups import unchanged.

The changes since 0.2.0 are `62895d3` (microphone loss), `436483d` (Markdown export) and the
version-bump commit that carries this file.
