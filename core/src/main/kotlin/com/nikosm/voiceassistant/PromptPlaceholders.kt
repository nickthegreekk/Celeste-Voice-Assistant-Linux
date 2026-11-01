package com.nikosm.voiceassistant

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * M18.4: prompt placeholders, reimplemented from the idea rather than copied.
 *
 * A persona prompt can contain `{{cur_date}}`, `{{user}}`, `{{model_name}}` and friends. They
 * resolve at composition time — never in the stored persona — so the same prompt means "today"
 * tomorrow. Unknown keys are left exactly as written, which is what lets a prompt discuss a
 * literal `{{example}}` without the app eating it.
 *
 * Values are deliberately a small desktop set: date/time/timezone, the effective model, the
 * persona, the user's chosen nickname, the configured location and the OS. Android's battery and
 * device fields are not invented here; a desktop has no battery worth prompting about.
 */
internal data class PlaceholderValues(
    val date: String,
    val time: String,
    val dateTime: String,
    val timezone: String,
    val modelName: String,
    val personaName: String,
    val userName: String,
    val location: String,
    val os: String,
)

internal fun placeholderValues(
    now: ZonedDateTime,
    modelName: String,
    personaName: String,
    userName: String,
    location: String,
    os: String,
): PlaceholderValues = PlaceholderValues(
    date = now.toLocalDate().toString(),
    time = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
    dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
    timezone = now.zone.id,
    modelName = modelName,
    personaName = personaName,
    userName = userName.ifBlank { "user" },
    location = location,
    os = os,
)

/** The live values for one turn: settings, the clock, the effective model and the OS. */
internal fun AssistantService.placeholderValuesFor(
    persona: Persona,
    now: ZonedDateTime = ZonedDateTime.now(),
): PlaceholderValues {
    val rawModel = persona.model.trim()
    val model = if (rawModel.startsWith("[") && rawModel.contains("] ")) {
        rawModel.substringAfter("] ")
    } else {
        rawModel
    }
    val os = listOfNotNull(System.getProperty("os.name"), System.getProperty("os.version"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    return placeholderValues(
        now = now,
        modelName = model,
        personaName = persona.name,
        userName = settingsManager.getUserNickname(),
        location = settingsManager.getUserLocation().orEmpty(),
        os = os,
    )
}

private val placeholderPattern = Regex("""\{\{\s*([a-zA-Z0-9_]+)\s*\}\}""")

/**
 * Replace known `{{keys}}` and leave everything else untouched. Pure; the clock is in the values
 * so a test can pin the output.
 */
internal fun resolvePlaceholders(text: String, values: PlaceholderValues): String =
    placeholderPattern.replace(text) { match ->
        // Locale.ROOT, not the machine's locale: a Turkish default would lowercase
        // `{{TIMEZONE}}`'s I to a dotless letter and silently stop matching the key.
        when (match.groupValues[1].lowercase(Locale.ROOT)) {
            "cur_date", "date" -> values.date
            "cur_time", "time" -> values.time
            "cur_datetime", "datetime" -> values.dateTime
            "timezone" -> values.timezone
            "model_name", "model" -> values.modelName
            "char", "persona" -> values.personaName
            "user" -> values.userName
            "location" -> values.location
            "os", "system_version" -> values.os
            else -> match.value
        }
    }
