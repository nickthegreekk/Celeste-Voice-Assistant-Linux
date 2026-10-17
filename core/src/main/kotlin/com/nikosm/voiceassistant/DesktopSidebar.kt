package com.nikosm.voiceassistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * M17: the main window's sidebar — personas and rooms beside the conversation.
 *
 * This is the desktop shape of what the persona picker and the room picker did on separate
 * screens. It is hidden below [DESKTOP_SIDEBAR_MIN_WIDTH], so a narrow window keeps the ported
 * layout exactly.
 *
 * **"Chats" are personas here.** Upstream's model is one conversation per persona (plus rooms),
 * not a list of independent threads; a sidebar row that opened a thread list would be a new
 * feature, and §10 says the upstream app is the behavioural reference. So selecting a row switches
 * the conversation, which is what "open that chat" means in this app.
 *
 * The component is stateless: it reports a selection and paints whatever the caller says is
 * current. Both callbacks go through the same service paths as the picker (`switchPersonaFromPicker`
 * and `switchRoom`), so a row cannot move the selection without moving the transcript.
 */
@Composable
fun DesktopSidebar(
    personas: List<Persona>,
    currentPersonaName: String,
    rooms: List<Room>,
    activeRoomId: String?,
    accentColor: Color,
    onPersonaSelected: (Persona) -> Unit,
    onRoomSelected: (Room) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(272.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)) {
            Text(
                text = "CELESTE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp)
            ) {
                SidebarSection(title = "Personas")
                personas.forEach { persona ->
                    SidebarRow(
                        title = persona.name,
                        subtitle = persona.model,
                        // A room replaces the persona conversation in the body, so while a room
                        // is open no persona row is "current" — that would name a transcript
                        // the window is not showing.
                        active = activeRoomId == null && persona.name == currentPersonaName,
                        accentColor = accentColor,
                        leading = {
                            ProviderLogo(
                                icon = persona.providerIcon,
                                isCloud = persona.isCloud,
                                size = 28.dp,
                                imagePath = persona.iconImageUri
                            )
                        },
                        onClick = { onPersonaSelected(persona) }
                    )
                }

                if (rooms.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SidebarSection(title = "Rooms")
                    rooms.forEach { room ->
                        SidebarRow(
                            title = room.name,
                            subtitle = room.memberNames.size.let { if (it == 1) "1 member" else "$it members" },
                            active = room.id == activeRoomId,
                            accentColor = accentColor,
                            leading = {
                                Icon(
                                    Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            onClick = { onRoomSelected(room) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun SidebarRow(
    title: String,
    subtitle: String,
    active: Boolean,
    accentColor: Color,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (active) accentColor.copy(alpha = 0.16f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) accentColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** M17: the width at which the main window grows its sidebar (a maximised desktop window). */
val DESKTOP_SIDEBAR_MIN_WIDTH = 1100.dp
