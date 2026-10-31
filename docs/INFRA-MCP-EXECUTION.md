# Execution plan — report and suggest, over host-exec-mcp

**Author:** ServerBox agent. **Updated:** 2026-10-31, revision 4 (read half deployed).
**Status:** **deployed 2026-10-31** (reviewer-signed), plus a capability fix the same evening.
15 tools live in CT 104
(`host_exec_server.py` `6a0d5f62…`, `hosts.json` `3f1ef48c…`), guards `0fc9297…` on all four hosts,
115 tests green. The write half remains unbuilt by design.
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

Live in CT 104 after deployment: `host_exec_server.py` `6a0d5f62…`, `hosts.json` `3f1ef48c…`,
service `active`. Rollback copies (`*.pre-v2` … `*.pre-v6`, `.deployed-before`) remain in
`/root/hostexec-v2/`. The `/root/v3check/` scratch copy was deleted after the live pass went green.

## 1. Conformance to the eleven constraints

| # | Constraint | State |
|---|---|---|
| 1 | `old.ssd` protected | `protected:true, acknowledge:true` in config; status reports it as `info` with the word protected; `suggested_fix` never mentions it (test asserts this); no preview/apply exists to refuse it in — see 7 |
| 2 | No `move_vzdump_backups` off `old.ssd` | Nothing of the kind exists anywhere in the build |
| 3 | No `apply_fix` in host-exec | Grep finds no write verb, no `shell=True`, no `shutil`, no `os.remove`. The only subprocess is the ssh argv executor |
| 4 | Read half finished | `get_system_status`, `get_backup_status`, `get_task_log`, `get_service_log`, `get_file_log`, `get_disk_health` — multi-host aggregation, 5 s/host, 3.1 s end to end after SSH multiplexing, partial results, JSON everywhere |
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

Suite: **115 passed** after the four live-pass fixes (the initial staged suite was 111; 52 test
functions, parametrised), run against the staged code with the existing venv. Network-free.

## 4. Inventory decision (resolved 2026-10-31)

**Keep all three measured Proxmox VE nodes as PVE:** ziptie (node `ziptie`), 5800x (node
`home-server`) and jasoninn (node `nas`). Do not restrict `pve_*` to jasoninn. ziptie's weekly
warned vzdump is the motivating case; hiding its backup visibility would defeat the integration.
`adrian-nas` is not Proxmox and keeps no `pve_node`. The read tools are allowlisted, so this adds no
write risk.

Plan corrections applied in `docs/INFRA-MCP-PLAN.md`: `pve_node: nas` for jasoninn; the ~174 GB
figure belongs to ziptie's `/mnt/hdd2tb`, while jasoninn's `hdd2tb` has ~708 GiB free. Both PVE
nodes' job configs must be read before proposing a space fix.

## 5. Deployment procedure (executed 2026-10-31 — see §7)

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
- The ziptie vzdump warning is `EFI disk without 'ms-cert=2023k'`: a VM EFI configuration change, not
  a disk fix. The read tool suggests it but nothing executes it. If the VM is on `old.ssd`, the
  protection rule applies; decide separately whether to do it manually or build a specific tool.
- **Tool-list cache after a server upgrade.** The server's `initialize` advertises
  `tools.listChanged=false`, so a connected client keeps its cached list. After the 15-tool
  deployment the Android app still showed the old 9 until the client was restarted; removing and
  re-adding the same server definition reuses the live registry connection. Restart clients after a
  tool-set change, or have the server advertise `listChanged=true` and send
  `notifications/tools/list_changed`.
- **Multiple host-check servers disagree.** `:8003` (host-exec) reports all four reachable; `:8002`
  (backup-status) reports ziptie reachable but 5800x permission-denied because 5800x has no
  backup-status collector or key; a third “Secret” server in the external system-check report says
  ziptie refused and 5800x healthy. The third server is not on CT 104; get its URL before acting.
  Do not reboot ziptie — both CT 104 servers reach it. Drop 5800x from `:8002`'s inventory; host-exec
  covers it. Use `:8003` for host health and `:8002` only for jasoninn/ziptie backup details.

## 7. Deployment outcome (2026-10-31)

Executed in the approved order: guards on all four hosts and hash-verified **before** the restart;
then the server and config; then the nine old tools, which all answered; then the live pass.

The live pass is what earned its keep — it found four defects the unit tests could not:

1. **Proxmox hosts timed out inside their own collector.** Eight sequential SSH calls took 5.4 s
   against a 5 s budget, so ziptie and jasoninn were reported unreachable while their old tools
   answered fine. Fixed with SSH connection multiplexing (`ControlMaster=auto`,
   `ControlPath=/run/host-exec-cm-%C`, `ControlPersist=60`): the same eight calls take 2.8 s.
   All four hosts now report in **3.1 s** end to end.
2. **`thermal` matched boot announcements** — `Thermal Zone [TZ01] (30 C)` is not an event. The rule
   now requires `over-temperature`, `critical temperature`, `thermal shutdown|event|trip|alert` or
   `throttl`.
3. **Pseudo filesystems and wrapped df rows produced findings** — `efivarfs` is always 100 %, and a
   wrapped line yields an empty mount name (`" at 89.9%"`). Only real `/dev/*` mounts with a name
   are classified now.
4. **The same filesystem was reported twice** — once by `pvesm`, once by `df`. The storage inventory
   is the authority for its own mounts; a df row is suppressed only when `pvesm` reports that mount,
   and `acknowledge` is carried through from config so ziptie's backup disk reads `info`.

Final live state: 4/4 hosts reachable, 3 findings — `hdd2tb` 89.9 % **info** (acknowledged),
`old.ssd` 87.8 % **info** (protected), and one real **warning**: ziptie's last vzdump reports
`WARNINGS: 1`. Exactly one suggested fix, for the backup warning; nothing mentions `old.ssd`.
`get_task_log` on that task surfaces the cause: `WARN: EFI disk without 'ms-cert=2023k' option`.

Also verified live: `get_disk_health` on ziptie and 5800x (all disks PASSED; the 320 GB WD Blue shows
one historical CRC error and no pending sectors), `get_file_log(adrian-nas, smb)` returning the
`directoty mask` Samba typo, and `get_service_log` answering `unsupported` on adrian with no SSH.

Rollback material stays in `/root/hostexec-v2/` (`*.pre-v2` … `*.pre-v6`, `.deployed-before`).
The scratch test directory `/root/v3check/` was deleted after the pass went green.

## 8. Capability advertisement (2026-10-31, after deployment)

A phone that connected before the 15-tool deployment kept serving the cached nine. The endpoint was
fine: `tools/list` returned 15 in one page with no cursor. The cause was on the server side of the
protocol — `FastMCP` builds its capabilities from `NotificationOptions()` defaults, so it advertised
`tools.listChanged: false`, and a client may then cache the list indefinitely.

Fixed by wrapping `mcp._mcp_server.create_initialization_options` and passing
`NotificationOptions(tools_changed=True)`; there is no constructor argument for it in mcp 1.30.0.
Verified live: `initialize` now returns `tools: listChanged=True`, and `tools/list` still returns 15
in a single page. 116 tests (one new: the capability is advertised).

Honest limits of this fix:

- It advertises that the list *may* change; it does not push a change to an already-stale client.
  This server's tool set changes only when the process restarts, and a restart drops every session,
  so `notifications/tools/list_changed` has nothing to notify. The phone still needs one reconnect
  (force-stop or reboot) to refetch; after that it is told the list can change and can refetch.
- `serverInfo.version` currently reports `1.30.0` (the SDK's version), not this server's `VERSION`,
  because FastMCP falls back to the package version when none is given. Cosmetic; one argument to fix.
