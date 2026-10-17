package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The skill editor's schedule section (PLAN.md §10/M15).
 *
 * A schedule is a separate [ScheduledSkill] record rather than a field on [Skill] (Models.kt is a
 * COPY file — see the record's header), so this section reads the live list from the service
 * instead of the [Skill] passed to the editor. That also makes it observe an import or a
 * restore while the editor is open, which an embedded field could not.
 *
 * The record is created when the switch is turned on and KEPT when it is turned off: off means
 * "stop running", not "forget the time and persona". Deleting the record is the same act as
 * deleting the skill, and the service does that for the user.
 *
 * The time field commits only while it parses ([parseScheduledTime]); unparseable text is a
 * warning and leaves the stored time alone, so a half-typed "09:3" never becomes a schedule at
 * an unintended minute.
 */
@Composable
internal fun SkillScheduleSection(
    service: AssistantService,
    skillId: String,
    personas: List<Persona>,
) {
    val schedules by service.scheduledSkills.collectAsState()
    // Collected live so the eligibility line follows a server being added or deleted while this
    // editor is open — the same reason PersonaEditor passes the live sets into its spontaneous
    // section rather than a snapshot taken when the editor opened.
    val ollamaBases by service.ollamaBaseUrls.collectAsState()
    val cloudApis by service.cloudApis.collectAsState()
    val customCloudApis by service.customCloudApis.collectAsState()

    val schedule = schedules.firstOrNull { it.skillId == skillId }
    val storedMinute = schedule?.minuteOfDay ?: DEFAULT_SCHEDULED_SKILL_MINUTE
    var timeText by rememberSaveable(skillId) { mutableStateOf(formatScheduledTime(storedMinute)) }
    var personaExpanded by remember { mutableStateOf(false) }

    val parsedTime = parseScheduledTime(timeText)
    val ollamaNames = remember(ollamaBases) { ollamaBases.map { it.name }.toSet() }
    val cloudNames = remember(cloudApis, customCloudApis) {
        (cloudApis + customCloudApis).map { it.name }.toSet()
    }
    val selectedPersona = personas.firstOrNull { it.name == schedule?.personaName }
    val eligibility = selectedPersona?.let {
        scheduledSkillEligibility(it, ollamaNames, cloudNames)
    }
    val enabled = schedule?.enabled == true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Schedule, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Run on a schedule", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (schedule == null) {
                        "Not scheduled. The skill fires only on its trigger phrases."
                    } else {
                        scheduledSkillSummary(schedule, personas.map { it.name })
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (on) {
                        service.setScheduledSkill(
                            schedule?.copy(enabled = true) ?: ScheduledSkill(
                                skillId = skillId,
                                personaName = personas.firstOrNull()?.name.orEmpty(),
                                minuteOfDay = parsedTime ?: DEFAULT_SCHEDULED_SKILL_MINUTE,
                                enabled = true
                            )
                        )
                    } else {
                        schedule?.let { service.setScheduledSkill(it.copy(enabled = false)) }
                    }
                },
                modifier = Modifier.testTag("skillScheduleSwitch")
            )
        }

        if (enabled) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = timeText,
                onValueChange = { text ->
                    timeText = text
                    // Only a parseable value is written; see the section header.
                    parseScheduledTime(text)?.let { parsed ->
                        schedule?.let { current ->
                            if (current.minuteOfDay != parsed) {
                                service.setScheduledSkill(current.copy(minuteOfDay = parsed))
                            }
                        }
                    }
                },
                label = { Text("Time (HH:MM)") },
                placeholder = { Text("08:00") },
                singleLine = true,
                supportingText = {
                    Text("Once every day, including weekends. Local time.")
                },
                modifier = Modifier.fillMaxWidth().testTag("skillScheduleTime")
            )
            if (parsedTime == null) {
                Text(
                    text = "Use HH:MM — the schedule keeps the last valid time until this parses.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedPersona?.name
                        ?: schedule?.personaName?.takeIf { it.isNotBlank() }
                        ?: "Choose a persona",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Speaks as") },
                    modifier = Modifier.fillMaxWidth().testTag("skillSchedulePersona"),
                    trailingIcon = {
                        IconButton(onClick = { personaExpanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                )
                DropdownMenu(
                    expanded = personaExpanded,
                    onDismissRequest = { personaExpanded = false }
                ) {
                    personas.forEach { persona ->
                        DropdownMenuItem(
                            text = { Text(persona.name) },
                            onClick = {
                                personaExpanded = false
                                schedule?.let {
                                    if (it.personaName != persona.name) {
                                        service.setScheduledSkill(it.copy(personaName = persona.name))
                                    }
                                }
                            }
                        )
                    }
                }
            }

            when {
                schedule == null -> Unit
                schedule.personaName.isBlank() -> Text(
                    text = "Pick a persona — a schedule with no speaker will never run.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                selectedPersona == null -> Text(
                    text = "Persona '${schedule.personaName}' no longer exists — this schedule " +
                        "will never run until you pick another.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                eligibility is RoomEligibility.Blocked -> Text(
                    text = (eligibility as RoomEligibility.Blocked).reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = "Celeste has to be running for the run to happen — the clock is a timer " +
                    "in this process. Nothing is scheduled while the application is closed, and " +
                    "a time that passes while it is closed is skipped rather than replayed.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }

        // A record kept while the schedule is off is still visible as its summary above; this
        // line is only reachable when the switch is off and the user wonders where it went.
        if (!enabled && schedule != null) {
            Text(
                text = "Off keeps the time and persona; the skill runs only when the switch is on.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}
