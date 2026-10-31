# Execution plan — report and suggest, over host-exec-mcp

**Author:** ServerBox agent. **Updated:** 2026-10-31, revision 3 (read half complete).
**Status:** staged and green — **nothing deployed**. The live service in CT 104 is unchanged and
still serves the original nine tools. Deployment waits for review.
**Contract:** `docs/INFRA-MCP-PLAN.md` (owned by another agent). This file does not edit it.

## 0. Where the files are

Staged on the **Jason Inn NAS** (ServerBox `pXCtwfb00`, hostname `nas`) under **`/root/hostexec-v2/`**:

| File | Bytes | sha256 (first 16) |
|---|---|---|
| `host_exec_server.py` | 66,231 | `84f3182181080f23` |
| `host-exec-guard` | 5,429 | `0fc929709e4994c4` |
| `hosts.json` | 2,007 | `86f8bcb3bc192735` |
| `test_host_exec.py` | 30,165 | `74edeb968d950c36` |
| `*.pre-v3`, `*.pre-v2` | — | earlier revisions, for diffing |

Live in CT 104, untouched: `host_exec_server.py` = `dda377aee377b8dd…`, service `active`.
A scratch copy used only for running the suite lives in `/root/v3check/` inside CT 104; **delete it
once this is reviewed** — nothing uses it.

## 1. Conformance to the eleven constraints

| # | Constraint | State |
|---|---|---|
| 1 | `old.ssd` protected | `protected:true, acknowledge:true` in config; status reports it as `info` with the word protected; `suggested_fix` never mentions it (test asserts this); no preview/apply exists to refuse it in — see 7 |
| 2 | No `move_vzdump_backups` off `old.ssd` | Nothing of the kind exists anywhere in the build |
| 3 | No `apply_fix` in host-exec | Grep finds no write verb, no `shell=True`, no `shutil`, no `os.remove`. The only subprocess is the ssh argv executor |
| 4 | Read half finished | `get_system_status`, `get_backup_status`, `get_task_log`, `get_service_log`, `get_file_log`, `get_disk_health` — multi-host aggregation, 5 s/host, 5.5 s gather, partial results, JSON everywhere |
| 5 | Nine prose tools kept | All nine still registered; their output is unchanged |
| 6 | `suggested_fix` advisory | Text only: action, why, steps, risk, rollback, evidence. No command string. Nothing executes it; `info` findings produce no suggestion at all |
| 7 | Phase gates | Write half absent: no ops server, no plan token, no nonce store. They belong to a later, separate decision |
| 8 | adrian-nas has no systemd | `get_service_log` returns `{"unsupported": "host has no systemd"}` with no SSH attempt; `get_file_log` uses the allowlist; an unreadable log becomes a finding, never a failed report |
| 9 | Root is provisioning only | Keys stay on CT 104; the MCP surface is semantic; no raw shell; redaction on every result, error, audit line |
| 10 | Fixture ≠ production | Fixture now mirrors all four hosts (ziptie, 5800x, jasoninn, adrian) with fake key paths. **Resolved, see §4** |
| 11 | Tests network-free | Transport stubbed in every test; no real address or key path in the suite; `/tmp/fake-key-*` only |

## 2. Tools (15 registered: 9 prose + 6 JSON)

| Tool | Kind | Notes |
|---|---|---|
| `get_system_status(hosts="")` | JSON | per-host uptime/load/mem/disks/inodes/storages/failed units/last backup + `findings[]` + `suggested_fix[]` |
| `get_backup_status(host, limit=10)` | JSON | vzdump tasks with guest, duration, status; non-OK → `backup` warning |
| `get_task_log(host, task_id, max_lines=200)` | JSON | warning/error lines **plus one line of context each side**, `matched_warning_or_error` flag |
| `get_service_log(host, unit, since="24h", max_lines=200)` | JSON | `journalctl -p err..warning`; `since` ∈ {1h, 24h, 7d}; unsupported on adrian |
| `get_file_log(host, log_name, lines=50)` | text | allowlisted names only (`smb`, `kern`, `syslog` on adrian) |
| `get_disk_health(host)` | text | `smartctl -H -A` per disk; flags verdict failure, reallocated, pending, offline-uncorrectable |

Findings: `id`, `host`, `area`, `severity` (`info|warning|critical`), `summary`, `evidence[]`
(redacted, ≤500 chars), `confidence`, `observed_at`.

Allowlist additions, enforced in **both** `_is_allowed()` and the host guard: `df -i`,
`pvesm status`, `lsblk -dno NAME,TYPE,SIZE,MODEL`, `smartctl -H -A /dev/<dev>`, `tail -n N <one of
three allowlisted paths>`, `systemctl list-units --state=failed …`, `journalctl -k -p err..warning …`,
`journalctl -u <unit> -p err..warning -n N --since <-1h|-24h|-7d> --no-pager`.

## 3. What the tests caught

**A real bug**: the smartctl rule read `argv[:2] == ["smartctl", "-H", "-A"]` — a two-element slice
compared against a three-element list, so it could never match and every `get_disk_health` call was
refused by its own allowlist. Fixed on both sides; `test_disk_health_*` now pass.

Three test expectations were also wrong once the fixture matched production: `pve_task_list` needed a
host that really has no `pve_node` (adrian, not 5800x); `list_hosts` now prints four lines; the
acknowledged-disk case needed a mount actually over 85 % to produce the `info` finding.

Suite: **111 passed** (52 test functions, parametrised), run in `/root/v3check` against the staged
code with the existing venv. Network-free.

## 4. Inventory decision (resolved 2026-10-31)

**Keep all three measured Proxmox VE nodes as PVE:** ziptie (node `ziptie`), 5800x (node
`home-server`) and jasoninn (node `nas`). Do not restrict `pve_*` to jasoninn. ziptie's weekly
warned vzdump is the motivating case; hiding its backup visibility would defeat the integration.
`adrian-nas` is not Proxmox and keeps no `pve_node`. The read tools are allowlisted, so this adds no
write risk.

Plan corrections applied in `docs/INFRA-MCP-PLAN.md`: `pve_node: nas` for jasoninn; the ~174 GB
figure belongs to ziptie's `/mnt/hdd2tb`, while jasoninn's `hdd2tb` has ~708 GiB free. Both PVE
nodes' job configs must be read before proposing a space fix.

## 5. Deployment (NOT executed)

1. Push the four files; `chmod 640 /etc/host-exec-mcp/hosts.json`.
2. Re-install the guard on four hosts, verifying sha256 `0fc929709e4994c4…` on each: ziptie,
   jasoninn, 5800x as root into `/usr/local/sbin/`; **adrian-nas** as `adrian` into
   `/home/adrian/.local/bin/` (its `authorized_keys` line already points there, and that account has
   no passwordless sudo).
3. `systemctl restart host-exec-mcp`, then run the suite in place.
4. Live pass: status for all four under 20 s (with 5800x awake and asleep), `get_disk_health` on
   ziptie and 5800x, `get_file_log(adrian, smb)`, `get_service_log` on ziptie, and the nine old tools
   still answering.
5. Rollback: restore `*.pre-v3`, restart. Guards may stay; the new shapes are additive.

## 6. Still open

- `get_disk_health` stays a separate tool; SMART on every status call would exceed the 20 s budget.
- The SMB `directoty mask` typo is a host configuration fix, approved separately; the status report
  keeps surfacing it until fixed.
