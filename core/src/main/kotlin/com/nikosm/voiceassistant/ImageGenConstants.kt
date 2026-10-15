package com.nikosm.voiceassistant

/**
 * BORROWED FROM M1
 *
 * Defined upstream in `AssistantServiceImageGen.kt:74`, which will absorb this file when it is
 * ported. `grep -rl "BORROWED FROM M1" core/` is the M1 checklist (AGENTS.md §3).
 *
 * Why this exists at all: `ComfyWorkflow.kt` is an m0 file (PLAN.md §7a) and uses this constant
 * as the default for `ComfyPromptValues.negative` at line 200, but upstream defines it inside
 * `AssistantServiceImageGen.kt`, which is m1 because it extends `AssistantService`.
 *
 * The two other ways out were both worse. Moving `ComfyWorkflow.kt` to m1 would exile
 * `ComfyPromptFillingTest` and `ComfyWorkflowValidationTest` from M0 — the best-tested logic
 * and the reason M0 has a test deliverable at all. Editing `ComfyWorkflow.kt` to inline the
 * literal would be a permanent diff on a COPY file for the sake of one string. So the constant
 * is borrowed instead: one declaration, deleted from here in the same commit that lands
 * `AssistantServiceImageGen.kt`, and recorded in `docs/PROVENANCE.md`.
 *
 * The declaration below is upstream's, byte-for-byte, doc comment included — same name, same
 * type, same literal. Nothing else is ever added to this file.
 */

/**
 * The negative prompt Celeste supplies to `%NEGATIVE%` in a ComfyUI template — the same literal
 * A1111 has always been sent. One constant so the two backends cannot drift.
 */
internal const val IMAGE_GEN_NEGATIVE_PROMPT =
    "blurry, distorted, low quality, extra limbs, deformed"
