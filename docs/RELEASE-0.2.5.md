# Celeste 0.2.5

A small follow-up to 0.2.4. The TTS/Ollama fixes from 0.2.4 are unchanged.

## What changed

**Gateway transcription refuses missing credentials.** On the voice path, a
BASIC gateway entry with an empty password (or an API-key entry with no key)
used to build a request as `Basic user:` and let the server answer a bare 401.
`transcribeWithGateway` now checks the shared credential guard before building
any request, logs the field and entry that need fixing, and skips that gateway.
With failover enabled a valid fallback is still tried; when none remain, the
turn fails with the named-field message instead of an unexplained 401.

## Packages

| Package | Size | SHA-256 |
|---|---:|---|
| `celeste-linux-0.2.5-1-x86_64.pkg.tar.zst` (Arch) | 112,310,749 B | `5e2b12710836c7dafbd3684f359d4097aaaab8a750195cb3b6c26108e79b0f00` |
| `celeste-linux_0.2.5_amd64.deb` (Debian) | 100,489,016 B | `98e619f9747adaf3b37997db2299dc098d03a835475484638411ec7bba20382f` |
| `celeste-linux-dbgsym_0.2.5_amd64.deb` | 1,450,868 B | `15b26b6ed65040395584e0a007e037e7c8b24cd8d8344e39a40aab2bfa58fbe1` |

Install:

```sh
# Arch
sudo pacman -U celeste-linux-0.2.5-1-x86_64.pkg.tar.zst

# Debian
sudo apt install ./celeste-linux_0.2.5_amd64.deb
```

The Debian package was built in the `packaging/debian/Containerfile` Debian 13
environment, so its bundled runtime links Debian's glibc.

## Verification

- `./gradlew build` — green; 1062 core tests and 94 platform tests, 0 failures.
- `GatewayCredentialResolutionTest` includes the new missing-password case: no
  `/transcribe` request is sent and the turn carries a "no password" error.
- Live Linux checks from 0.2.4 remain the last device run (1, 2, 3, 5, 6 and 7
  passed; 4 is blocked because Kokoro has no Greek voice).
