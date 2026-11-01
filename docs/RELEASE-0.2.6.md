# Celeste 0.2.6

Persona context, prompt placeholders, and a screen tool that asks before it looks.
The 0.2.5 gateway fix is unchanged.

## What changed

**Lorebooks and visible context.** A shared library of background entries, activated
either always or by keywords in the current message and the last few turns. Each entry
has its own enabled switch, case sensitivity and scan depth; the injected block is
labelled user-authored context and is capped. The chat now shows a compact row under the
user message naming what the turn actually injected: memory notes, matched skills and
activated lorebooks (with the keyword or "always" that fired).

**PDF/DOCX attachments and OCR.** A PDF goes through poppler's `pdftotext`; a DOCX is
read with the JDK's ZIP + XML. A page with almost no extractable text is rendered with
`pdftoppm` and read by the persona's direct Ollama vision model. Caps: 100 pages, 10 OCR
pages, 150 dpi, 200,000 characters. Nothing leaves the machine except to the model the
user configured.

**Prompt placeholders.** `{{cur_date}}`, `{{cur_time}}`, `{{cur_datetime}}`,
`{{timezone}}`, `{{model_name}}`, `{{char}}`, `{{user}}`, `{{location}}` and `{{os}}` —
plus the aliases `{{date}}`, `{{time}}`, `{{datetime}}`, `{{model}}`, `{{persona}}` and
`{{system_version}}` — resolve when a request is composed. The stored prompt is never
rewritten, and an unknown key is left exactly as written. Settings → General → Identity
sets the nickname `{{user}}` resolves to; it is carried in backups as `userNickname`.

**Screen vision.** A new `look_at_screen` tool: the model can ask to see the screen, but
**every capture asks first** and there is no session trust. Off by default, with a global
and per-persona policy (Settings → Screen, and the persona editor). The whole screen is
captured, sent only to the persona's direct Ollama vision model, and only the model's
written description returns; the image is never added to the transcript or written to
disk. The audit keeps the question, outcome and capture size, never the image or its
description. Cloud and gateway-only personas are not offered the tool in v1.

**Terminal follow-ups.** Capped command output keeps both the beginning and the end, the
tool contract asks for one command per round, an abandoned output drain is reported as
incomplete, and a reply that claims a command result without a `run_command` call is
flagged in the chat.

## Packages

| Package | Size | SHA-256 |
|---|---:|---|
| `celeste-linux-0.2.6-1-x86_64.pkg.tar.zst` (Arch) | 112,526,171 B | `a1d6233e58f031cde861cc14491c227b63b2361985b4a4cb53399c2547716c26` |
| `celeste-linux_0.2.6_amd64.deb` (Debian) | 100,726,460 B | `ac276b7f8c676dc497663bcfdd79a549a0f4ec93197afb08f735961aaa2780dc` |
| `celeste-linux-dbgsym_0.2.6_amd64.deb` | 1,450,460 B | `dbe31a6995879a95888bda6e3e995a90fb0bcd523fc73f8c8f6f24aac2467fdc` |

Install:

```sh
# Arch
sudo pacman -U celeste-linux-0.2.6-1-x86_64.pkg.tar.zst

# Debian
sudo apt install ./celeste-linux_0.2.6_amd64.deb
```

The Debian package was built in the `packaging/debian/Containerfile` Debian 13
environment, so its bundled runtime links Debian's glibc.

## Verification

- `./gradlew build` — green; 1,141 core tests and 104 platform tests, 0 failures.
- Both artefacts were extracted and searched: `ScreenToolsKt` and `look_at_screen`,
  `ScreenCaptureKt`, `PromptPlaceholdersKt` and `LinuxScreenCapture` are present in the
  bundled `core`/`platform` jars, and `APP_VERSION` reads `0.2.6`.
- `tools/network-audit.sh` — every URL literal is recorded in PLAN.md §5; no new call
  is added by this release.
- `tools/sync-from-android.sh --check` — the same two pre-existing drift files as
  before this work; no new COPY edits.
- Screen capture was exercised live on this machine before packaging (audit entry:
  723,696 bytes, `ok`), and `import -window root png:-` was verified to emit a PNG
  header on the X11 desktop.
