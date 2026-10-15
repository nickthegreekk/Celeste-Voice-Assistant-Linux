package com.nikosm.voiceassistant

/**
 * BORROWED FROM M1
 *
 * Defined upstream in `AssistantServiceGateway.kt:118`, which will absorb this file when it is
 * ported. `grep -rl "BORROWED FROM M1" core/` is the M1 checklist (AGENTS.md §3).
 *
 * `normalizeGatewayUrl` lives here on its own because upstream keeps it somewhere we cannot
 * reach yet.
 *
 * Upstream defines it at `AssistantServiceGateway.kt:118`, inside a 655-line file that
 * references `AssistantService` 22 times — so it cannot arrive before M1, when the service
 * it belongs to is ported. But `ServerIdentity.kt` (an m0 file, PLAN.md §7a) calls it from
 * two places, and bringing the whole gateway file across just to reach one line of real
 * logic would drag M1's core into M0.
 *
 * So the line is defined once, here, with upstream's semantics unchanged: trim the
 * whitespace, drop trailing slashes, nothing else. No scheme is added, no path is
 * rewritten — callers concatenate paths onto the result, and that is why the trailing-slash
 * removal is the whole function.
 *
 * When `AssistantServiceGateway.kt` is ported at M1, its copy of this function is dropped in
 * favour of this one. One definition survives; the removal is an ADAPT and gets recorded in
 * `docs/PROVENANCE.md`.
 */
internal fun normalizeGatewayUrl(raw: String): String = raw.trim().trimEnd('/')
