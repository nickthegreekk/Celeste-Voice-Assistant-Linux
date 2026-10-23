# Celeste 0.2.2

**Release date:** 2026-10-23
**Platforms:** Arch Linux (x86_64), Debian 13 “trixie” (amd64)
**Upstream base:** `VoiceAssistant` @ `fe584ad0` (unchanged)

This release collects the 0.2.2 work already staged in the packaging changelog
(About section, unified icon, MCP per-server enable/disable with the trust gate,
per-persona memory) and adds credential-excluding backups, import hardening and a
fail-closed TLS trust manager. No dependency changes and no data migration.
Existing settings, personas, chats, memories and backups are compatible.

## New

### Backup export can exclude credentials

- Settings → Backup & Recovery has **Exclude credentials**. A redacted export clears
  passwords and API keys for servers, image generators, cloud providers and RAG, the
  RAG username, MCP environment values and headers, userinfo/query strings/fragments
  in URLs, and the value after credential flags in MCP arguments.
- A plain-text export that would still carry credentials asks first, with three
  actions: exclude and export, export with credentials anyway, or cancel. An
  encrypted export never asks, because the file is already encrypted.
- The redacted export is built with `BackupData.copy(...)`, so every field the
  redaction does not name — including persona chats, persona artwork, message images
  and scheduled skills — is carried through untouched. The previous constructor
  rebuild silently dropped those four fields.
- Redaction cannot find a secret typed into a chat, a persona prompt, or passed as a
  bare MCP argument. Chats and prompts are kept exactly as written.
- Trusted certificate pins are still exported. They are not secret, and import never
  restores them.

## Fixes

- **Import never confers trust.** MCP servers restore disabled with trust `ASK`, and
  trusted certificate pins are not restored.
- **The pinned TLS trust manager fails closed.** A null or empty certificate chain now
  throws `SSLHandshakeException("Empty certificate chain — connection rejected")`
  before the pins are read or the approval callback is offered. A known pin still
  passes; an unknown host still raises “New self-signed cert”; a changed certificate
  still raises “Cert changed!” and leaves the stored pin alone.

## Packages

| Package | Size | SHA-256 |
|---|---:|---|
| `celeste-linux-0.2.2-1-x86_64.pkg.tar.zst` (Arch) | 112,309,972 bytes | `65bb9dd1c30f7e2deb0b608b5b6089cac482d2fa78a189abd0ce29aea1b6018d` |
| `celeste-linux_0.2.2_amd64.deb` (Debian) | 100,487,240 bytes | `a0ca2e2abf1b828c983b876aaad6230b5f6925500c41549a65d8e0823299be39` |
| `celeste-linux-dbgsym_0.2.2_amd64.deb` (debug symbols) | 1,451,880 bytes | `748e3797b20d744cefd9dbf02d373ba9498e425c663d1f5c2ee498b8f76fa75e` |

Install:

```sh
# Arch
sudo pacman -U celeste-linux-0.2.2-1-x86_64.pkg.tar.zst

# Debian
sudo apt install ./celeste-linux_0.2.2_amd64.deb
```

## Verification

- `./gradlew build` — green; core tests 1040, 0 failures.
- `tools/network-audit.sh` — all 21 URL literals in `:core` recorded in PLAN.md §5; no
  new network call.
- `tools/sync-from-android.sh --check` — no drift; copied files byte-identical to the
  upstream pin. Upstream has moved since the pin; that is allowed and the copied files
  are the guard.
- Arch package built with `makepkg -f`; the packaging `check()` loaded the trimmed ONNX
  runtime and reported `onnx native loaded — session inputs: [input, state, sr]`.
- Debian package built on Debian 13 in the project's container environment; control
  version is `0.2.2`.
- Both packages were extracted and inspected: each carries `core-0.2.2-*.jar` with
  `PinnedTrustManager`, `ExportDecision` and `BackupRedactionKt`; the application
  version reads `0.2.2` in `AppInfoKt` and `McpConnection`; the ONNX jar carries only
  `linux-x64`.
- On-screen export checks on the merged tree: a ticked-box plain export contained no
  sentinel key, the confirmation dialog's exclude/export-anyway/cancel actions
  produced the expected files (cancel produced none), an encrypted export was an
  encrypted envelope with no plaintext fields, and a redacted export retained the
  persona chats (730 messages across 9 personas) and room messages.
- **Live acceptance:** pending. This file is the release body for that run; record the
  result before publishing.

## Known limitations

- Echo cancellation is still absent. Use headphones or a PipeWire `module-echo-cancel`
  sink.
- The other 0.2.0/0.2.1 limitations are unchanged: the MCP timeouts, the benign
  `PwPlayback` Finished/Stopped race, and the hard-link refusal heuristic.
- Redaction cannot detect a secret that is not in a named credential field — chat text,
  persona prompts and bare MCP arguments are exported as written. The export screen
  says so.

## Upgrading from 0.2.1

No migration is required. The packages replace the application image; settings,
personas, chats, memories and images stay under the existing XDG paths. Backups import
unchanged. A redacted export now keeps persona chats, artwork, message images and
schedules that the previous redaction dropped; backups written before this release
are unaffected.
