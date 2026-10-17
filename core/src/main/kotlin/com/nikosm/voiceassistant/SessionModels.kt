package com.nikosm.voiceassistant

/**
 * Session model override (PLAN.md §10/M11): the model a conversation uses for the rest of this app
 * run, chosen in the chat header, without editing the persona.
 *
 * ## Why it is not persisted
 *
 * A stored override is a model edit with fewer steps and no way to tell afterwards which one the
 * user meant; it would also have to be migrated, backed up and explained. The header labels it
 * **session**, the picker says the persona itself is unchanged, and the process ending is the
 * reset. Persisting a model is what the persona editor is for.
 *
 * ## Why the persona is copied, not the model string patched
 *
 * `sendTextMessageToServer` and `sendAudioToServer` take a `Persona` and derive routing, the tool
 * contract, voice settings and the apply guards from it. Swapping the model on a **copy** at those
 * two entry points means every consumer in the turn sees one consistent persona, and nothing else
 * about it can accidentally diverge from what the user configured.
 */

/** The override for [personaName], or null when this session uses the persona's own model. */
internal fun sessionModelOverrideFor(overrides: Map<String, String>, personaName: String): String? =
    overrides[personaName]

/** The persona as this turn should see it: the model replaced, every other field untouched. */
internal fun withSessionModel(overrides: Map<String, String>, persona: Persona): Persona =
    overrides[persona.name]?.let { persona.copy(model = it) } ?: persona

/** The map after choosing [model] for one persona; null or blank clears the override. */
internal fun updatedSessionModels(
    overrides: Map<String, String>,
    personaName: String,
    model: String?,
): Map<String, String> {
    if (personaName.isBlank()) return overrides
    return if (model.isNullOrBlank()) overrides - personaName else overrides + (personaName to model)
}

/**
 * The server a session picker should list for [persona], or null when it has no Ollama connection.
 *
 * The persona's model is `"[Server] model"` (or a bare name that belongs to its backend URL), and
 * the picker must list the same server the persona already uses — not the first one configured.
 */
internal fun sessionModelServer(persona: Persona, bases: List<ServerConfig>): ServerConfig? {
    val display = persona.model.trim()
        .takeIf { it.startsWith("[") && it.contains("] ") }
        ?.substringAfter("[")
        ?.substringBefore("]")
    return bases.firstOrNull { it.name == display }
        ?: bases.firstOrNull {
            persona.backendUrl.isNotBlank() && it.url.trimEnd('/') == persona.backendUrl.trimEnd('/')
        }
        ?: bases.firstOrNull()
}
