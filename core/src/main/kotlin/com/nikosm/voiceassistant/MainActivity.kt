package com.nikosm.voiceassistant

// Deleted: the `MainActivity` class, whole. It was a `ComponentActivity`: a `SharedPreferences`-
// free shell whose entire job was to bind the service (`bindService`, `ServiceConnection`,
// `AssistantBinder.getService()`), drive the Android lifecycle (`onCreate`/`onNewIntent`/
// `onResume`/`onDestroy`), keep the screen awake (`FLAG_KEEP_SCREEN_ON`), go edge-to-edge, and
// unpack the intents that carried a notification tap, a Quick Settings tile press and an
// `ACTION_SEND` payload.
//
// None of that survives as a class here, and the reason is PLAN.md §3/D1 — one JVM process. There
// is no binder to bind: the UI holds the `AssistantService` instance directly, and `:desktop`'s
// `Main.kt` constructs it before the first frame rather than the UI waiting for it to arrive. The
// activity-lifecycle callbacks become the window's: `onDestroy`'s unbind is the service's own
// `shutdown()`, and `onResume`'s `promoteToForeground()` is gone with the foreground service.
//
// The intent handling goes three different ways, and none of them is a port:
//   - the spontaneous-notification deep link belongs to the tray, which is M4's;
//   - the Quick Settings tile press goes with the tile, which §9 drops and M4's tray replaces;
//   - the `ACTION_SEND` payload goes with the share target, which §2 leaves out of v1.
//
// What remains in this file are the composables that lived below the class. They were never
// Android's — they are the persona list and its editor entry point, and they are `:core`'s like
// every other ported screen.


import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.nikosm.voiceassistant.ui.theme.VoiceAssistantTheme
import kotlinx.coroutines.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.foundation.gestures.scrollBy

// The persona editor's open state must survive Activity recreation (rotation), but
// Persona is kotlinx-serializable rather than Parcelable — persist it as JSON.
@Serializable
private data class EditingPersonaPayload(val index: Int, val persona: Persona)

private val EditingPersonaSaver = Saver<Pair<Int, Persona>?, String>(
    save = { editing ->
        editing?.let { (index, persona) ->
            Json.encodeToString(EditingPersonaPayload.serializer(), EditingPersonaPayload(index, persona))
        } ?: ""
    },
    restore = { encoded ->
        if (encoded.isEmpty()) null
        else runCatching {
            val payload = Json.decodeFromString(EditingPersonaPayload.serializer(), encoded)
            payload.index to payload.persona
        }.getOrNull()
    }
)


/**
 * Personas category screen: the list-then-editor pattern, one level below the top-level
 * Settings list.
 *
 * Body of [SettingsCategoryScreen]. Tapping a persona opens its editor in place, exactly
 * as before; the "add" control is now a trailing row in the list rather than a header
 * affordance, because there is no collapsible header row any more.
 */
@Composable
fun PersonaSettings(
    service: AssistantService,
    personas: List<Persona>,
    currentThemeColor: Color,
    /**
     * Row to open the editor on as soon as this screen appears — the chat header's persona icon.
     * One-shot: reported through [onInitialEditConsumed] and never re-applied, so a rotation
     * cannot re-open an editor the user has already closed.
     */
    initialEditingIndex: Int? = null,
    onInitialEditConsumed: () -> Unit = {}
) {
    var editingPersona by rememberSaveable(stateSaver = EditingPersonaSaver) { mutableStateOf<Pair<Int, Persona>?>(null) }

    // The shortcut's open request, honoured exactly once. Keyed on the request itself, so the
    // normal path (null, and null again after the latch has fired) does nothing at all.
    LaunchedEffect(initialEditingIndex) {
        val index = initialEditingIndex ?: return@LaunchedEffect
        // Consume BEFORE acting: an index the list no longer has must still be spent, or it
        // would re-fire on the next recomposition and ambush whatever opened next.
        onInitialEditConsumed()
        personas.getOrNull(index)?.let { editingPersona = index to it }
    }

    // One-shot staleness guard: when the Activity is recreated while the editor is open,
    // the restored snapshot can be older than the service's copy (edits are debounced
    // into the service while editing). Re-resolve it against the current list so the
    // editor doesn't show — and later re-commit — outdated values. On a fresh open,
    // editingPersona is still null at first composition, so this is a no-op.
    LaunchedEffect(Unit) {
        val (index, snapshot) = editingPersona ?: return@LaunchedEffect
        if (index == -1) return@LaunchedEffect // brand-new persona: the snapshot is the source of truth
        personas.find { it.name == snapshot.name }?.let { fresh ->
            if (fresh != snapshot) editingPersona = index to fresh
        }
    }

    // PersonaEditor is a plain Column, so it adds no scrollable of its own and needs no
    // height bound: a category screen caps nothing and scrolls nothing itself.
    if (editingPersona == null) {
        // Plain Column, not LazyColumn: the category page already scrolls, and a nested
        // lazy list inside a vertical scroll parent fights it for the drag.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            personas.forEachIndexed { index, persona ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { editingPersona = index to persona },
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, persona.themeColor.copy(alpha = 0.3f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProviderLogo(icon = persona.providerIcon, isCloud = persona.isCloud, size = 52.dp, imagePath = persona.iconImageUri)
                        // icon size is provider logo changed from 40 to 52 dp
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(persona.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(persona.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // Add to the application menu: one `.desktop` entry that opens straight
                        // into this persona's chat (PLAN.md §9's replacement for the launcher
                        // shortcut). Upstream *requested* a shortcut and the launcher asked the
                        // user; here the file is written directly and the snackbar says where it
                        // went, which is the same shape the gallery save uses. Idempotent: pressing
                        // it twice rewrites the entry rather than adding a second one.
                        IconButton(
                            onClick = { service.createPersonaShortcut(persona.name) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.AddToHomeScreen,
                                contentDescription = "Add to the application menu",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Reorder controls — same up/down pattern as the server and
                        // cloud-API lists. Order is user-visible: the persona selector
                        // and the deletion fallback ("first remaining persona") follow it.
                        IconButton(onClick = { service.movePersonaUp(index) }, enabled = index > 0, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { service.movePersonaDown(index) }, enabled = index < personas.size - 1, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                }
            }

            // "New persona" used to be an IconButton in the collapsible section header.
            // A category screen has no header row, so it is a trailing list row instead —
            // same seeded draft, always visible below the existing personas.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        editingPersona = -1 to DEFAULT_PERSONAS[0].copy(
                            name = "New Persona",
                            themeColor = PERSONA_PALETTE.random()
                        )
                    },
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, currentThemeColor.copy(alpha = 0.3f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, tint = currentThemeColor, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "New Persona",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = currentThemeColor
                    )
                }
            }
        }
    } else {
        val (index, persona) = editingPersona!!
        PersonaEditor(
            persona = persona,
            onSave = { updated ->
                if (index == -1) {
                    service.addPersona(updated)
                    // Switch from "add" to "edit" mode for this persona
                    val newIdx = service.personas.value.indexOf(updated)
                    if (newIdx != -1) editingPersona = newIdx to updated
                } else {
                    service.updatePersona(index, updated)
                }
            },
            onDelete = if (index != -1) { { service.removePersona(index); editingPersona = null } } else null,
            onCancel = { editingPersona = null },
            service = service
        )
    }
}



