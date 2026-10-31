# Infrastructure MCP — execution state and next steps

**Status:** execution document, 2026-10-31. It complements
[`INFRA-MCP-PLAN.md`](INFRA-MCP-PLAN.md); where the two disagree, this file records the actual
`hostexec-v2` state and the next step.
**Source reviewed:** the `test_host_exec.py` suite from `/srv/mcp/host-exec/tests` (pasted
2026-10-31), plus the protected-`old.ssd` correction.

## 1. What is already built

The read-only base is stronger than the plan assumed. The suite demonstrates:

| Area | Evidence in the suite |
|---|---|
| Two-sided allowlist and host guard | `test_allowlist_*`, `test_disallowed_command_never_reaches_ssh`, `test_guard_*`; dry-run is ignored on a real SSH session. |
| Strict argv, no shell | `sh -c`, metacharacters, traversal, `tail -f`, arbitrary `systemctl`, non-UPID task paths all refused before SSH. |
| Redaction | secret values, PEMs, `ssh-ed25519`, tokens, long hex; result, logs and audit are checked. |
| Audit | timestamp, tool, host, argv, exit code, duration, bytes, output SHA-256, truncation, timeout, error class; no secret values. |
| Failure honesty | unreachable vs not authorised, timeout, truncated output, non-empty stderr on failure; no traceback to the model. |
| Host aliases and notes | `zip`, `Zip Tie`, `5800`; a CT note is surfaced by `list_hosts`. |
| Proxmox task paging | the whole 648-line log is read, not the first 50; output capped; bad UPIDs refused. |
| Non-systemd hosts | `adrian`/sysvinit: `service_status` and `journal_tail` explain instead of calling systemd; `syslog_tail` uses the fixed path. |
| Status JSON | `get_system_status` findings, severity, kernel I/O critical, unreachable host as a finding, non-systemd survival, acknowledged storage; `suggested_fix` steps. |
| File logs | `get_file_log` allowlist, permission denied reported as a failure, no arbitrary path. |
| Disk health | SMART pending/offline sectors; “needs root” when `smartctl` is unprivileged. |
| Output discipline | 65,536-byte cap, truncation marker, JSON cap for paged Proxmox output. |

This is a sound **read-only, trusted** server. Keep `host-exec` trusted and keep `apply_fix` off it.
The suite is network-free; the timeout and cap tests spawn only `sleep` and `head`.

## 2. Gaps before the read half is complete

Ordered by what blocks the acceptance flow.

### P1 — multi-host status and the 20-second budget

The current status test calls `get_system_status("ziptie")` for one host. The plan requires
`get_system_status(hosts?: string[])` to aggregate all four, run per-host collectors in parallel,
use a 5 s per-host deadline and a 20 s total budget, and return partial results when a host is down.
The suite does not test the aggregate or the timing. Add it before calling phase 1 done.

### P1 — protected `old.ssd`

`old.ssd` hosts a VM and must stay as is. The suite has no `old.ssd` fixture and no protected-storage
rule. Add:

- `protected: true` (and `role: vm`) in the host config;
- a status finding that reports its usage as **info**, not a fix candidate;
- `suggested_fix` must never mention `old.ssd`, its mount path, or the VM;
- in phase 2, `preview_fix` and `apply_fix` refuse it; the host helper re-checks.

### P1 — the actual vzdump target

Do not assume `old.ssd` or `hdd2tb` is the job target. Add a read path for `/etc/pve/jobs.cfg` and
the task evidence, and a fixture with `jasoninn`, `old.ssd` protected and `hdd2tb` as a candidate.
If the target is `hdd2tb` (56.9 %, ~174 GB free), space is not the cause; the warning must be read
from the task log. No fix is proposed until that evidence exists.

### P2 — JSON backup and log tools

The current `pve_backup_status`, `pve_task_log` and `journal_tail` are prose. The plan's model needs
JSON:

- `get_backup_status(host)` — last 10 tasks, warning counts, target storage ID, mount path, free
  bytes.
- `get_task_log(host, task_id, max_lines)` — warning/error filter plus context.
- `get_service_log(host, unit, since, max_lines)` — systemd only; `unsupported` on `adrian-nas`.

Keep the prose tools for existing callers. Add the JSON tools alongside them.

### P2 — status envelope details

Add `observed_at`, storage roles/protected flags, stable finding IDs, and a per-host `reachable`
field in the aggregate. Ensure `suggested_fix` is advisory data, never a command the model is told
to execute; the approval flow is `preview_fix` → user → `apply_fix`.

### P2 — `adrian-nas` SMB log

The suite already proves permission denied is reported honestly. The host-side fix (service account
in the log-owning group, likely `adm`) is an agent task now that the agent has root. Until it is
done, the status must carry a `log_unreadable` warning and not fail the whole report.

### P3 — phase 2 write half

Not built and not to be added to `host-exec`:

- `preview_fix` on `host-exec` — read-only, signs the HMAC plan token;
- `infra-ops` — separate, untrusted, `apply_fix` only;
- host helper — protected-storage refusal, inventory fingerprint, copy/verify/remove;
- nonce store, expiry, semantic-argument match;
- tests for replay, expiry, mismatch and stale fingerprint.

The first fix is chosen from phase 1 evidence, not pre-committed. No action may touch `old.ssd`.

## 3. Execution checklist

### Step 1 — finish the read half

- [ ] Add `hosts?: string[]` aggregate to `get_system_status`; parallel collectors, 5 s per host,
      20 s total, partial results.
- [ ] Add `observed_at`, storage role/protected flags and stable finding IDs.
- [ ] Mark `old.ssd` protected; report it as info; exclude it from every `suggested_fix`.
- [ ] Add `get_backup_status`, `get_task_log`, `get_service_log` JSON tools; keep the prose tools.
- [ ] Read the actual job target and warning; add a `jasoninn` fixture with `old.ssd` protected and
      `hdd2tb` candidate.
- [ ] Add `log_unreadable` handling for the `adrian-nas` SMB log; fix the group membership as root
      and re-test.
- [ ] Add the new tests listed in §4; run the existing suite unchanged.

### Step 2 — live read acceptance

- [ ] Configure the persona's read server and trust `host-exec`.
- [ ] “Systems status update” returns all four hosts in under 20 s with one host down; no narration.
- [ ] The jasoninn warning is followed by actual log lines; the target storage is named.
- [ ] `old.ssd` is shown as protected VM storage; no suggested fix touches it.
- [ ] `adrian-nas` reports the SMB log honestly (unreadable or lines after the fix).

### Step 3 — phase 2 write half (only after step 1–2)

- [ ] Add `preview_fix` to `host-exec`; HMAC token, TTL, nonce, semantic args.
- [ ] Build `infra-ops` separately; trust **ASK**; its only tool is `apply_fix`.
- [ ] Build the host helper; refuse `old.ssd`; re-check the inventory fingerprint; copy/verify/remove.
- [ ] Test deny, accept, replay, expiry, parameter mismatch, stale fingerprint and protected storage.
- [ ] Choose the first fix from evidence. If the job targets `hdd2tb` and its free space is ample,
      a space fix is not justified; address the actual warning instead.

## 4. Tests to add

- `test_status_aggregates_hosts_in_parallel_with_one_slow_host` — elapsed < 20 s; partial result.
- `test_status_marks_old_ssd_protected_and_offers_no_fix` — info finding; no `old.ssd` in any
  suggested step.
- `test_status_reports_the_actual_backup_target` — from the job config/task evidence; no assumptions.
- `test_status_includes_observed_at_and_stable_finding_ids`.
- `test_backup_status_json_includes_target_and_free_space`.
- `test_task_log_filters_warning_error_with_context`.
- `test_status_output_is_capped_and_truncated`.
- `test_preview_refuses_protected_storage` (phase 2).
- `test_apply_rechecks_protected_storage` (phase 2).
- `test_apply_rejects_replayed_and_expired_tokens` (phase 2).
- `test_no_fix_touches_old_ssd` — a model-facing guard test, plus a server-side refusal.

## 5. Guardrails

- `host-exec` stays read-only and trusted; `apply_fix` belongs on the separate ASK server.
- `old.ssd` is protected: no move, delete, resize, or VM change; status only.
- The first fix is evidence-driven. If the actual target is `hdd2tb` with ample free space, do not
  propose a space fix; read the warning.
- No raw shell, no arbitrary path, no key material in a result, error or audit.
- 20 s total / 5 s per host; 24 KiB tool output; no secrets.
- `suggested_fix` is advisory data, not an instruction; the approval flow remains preview → user →
  apply.
- The suite stays network-free; live acceptance is the only network run.

## 6. How to run

```sh
/srv/mcp/host-exec/.venv/bin/python -m pytest -q /srv/mcp/host-exec/tests
```

Add the new tests to the same suite. Do not point tests at the real hosts; the recorder transport
and the `sleep`/`head` process tests are the pattern.

## 7. Open questions

- Where `get_system_status` should accept both a single host (current callers) and a list (the
  aggregate); keep the single-host form as a convenience.
- The actual vzdump target and warning; the tools must read them, not assume.
- Whether `suggested_fix` should include human-readable steps at all, or only a finding and a
  pointer to the evidence; if kept, it must never contain a raw command or touch `old.ssd`.
- The `adrian-nas` SMB log group and path; fix as root, then re-test.
- The phase 2 fix catalog after the evidence is known.
