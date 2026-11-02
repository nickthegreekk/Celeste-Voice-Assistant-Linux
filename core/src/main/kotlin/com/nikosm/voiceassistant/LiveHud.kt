package com.nikosm.voiceassistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The Live HUD: an immersive, audio-driven second view of the same voice surface.
 *
 * ## Why it is a separate composable, not a restyle of [CelestialHudBody]
 *
 * The Celestial HUD is an accepted feature with its own layout, controls and tests. The Live HUD
 * is a different composition: it fills a window instead of a voice body, it has its own chrome
 * (clock, persona, state, usage), and its visuals are built around a live level ring rather than a
 * planet. Keeping it separate means neither screen has to grow a mode flag, and the Celestial
 * toggle in Settings keeps meaning exactly what it meant before.
 *
 * ## What drives the motion
 *
 * One frame loop owns time, the level and a short level history. Time drives the rotations,
 * breathing and fallback cadences; the level is smoothed with separate attack and release so a
 * syllable is visible without a silent gap collapsing the ring. The loop writes Compose state
 * that the draw lambdas read, so the per-frame cost is a redraw of the canvases rather than a
 * recomposition of the transcript and controls. When [state] is IDLE or THINKING there is no
 * input to measure, and the level is a deliberately small decorative cadence — never presented as
 * microphone activity.
 *
 * ## The two audio sources
 *
 * LISTENING reads [AssistantService.micAmplitude], the same real peak level the Celestial HUD
 * uses. SPEAKING reads [AssistantService.speechAmplitude], measured from the TTS PCM the process
 * already holds (see [AudioEnvelope]). The system TTS route has no samples to measure, so when
 * that flow is zero the HUD uses its own cadence — a visible pulse that does not claim to be a
 * measurement. The core never opens the microphone: tapping it runs the same service start/stop
 * path as the classic screen.
 */
@Composable
fun LiveHudContent(
    service: AssistantService,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    val state by service.assistantState.collectAsState()
    val persona by service.currentPersonaFlow.collectAsState()
    val usage by service.sessionUsage.collectAsState()
    val messages by service.messages.collectAsState()
    val voiceDuration by service.voiceDuration.collectAsState()
    val fraction by service.ttsPlaybackFraction.collectAsState()
    val timestamps by service.ttsWordTimestamps.collectAsState()
    val micAmplitude by service.micAmplitude.collectAsState()
    val speechAmplitude by service.speechAmplitude.collectAsState()
    val muted by service.micMuted.collectAsState()
    val silenced by service.silenced.collectAsState()
    val handsFree by service.handsFreeMode.collectAsState()

    LiveHudBody(
        state = state,
        personaName = persona?.name ?: "Celeste",
        personaColor = persona?.themeColor ?: MaterialTheme.colorScheme.primary,
        usage = usage,
        messages = messages,
        voiceDuration = voiceDuration,
        ttsPlaybackFraction = fraction,
        ttsWordTimestamps = timestamps,
        micAmplitude = micAmplitude,
        speechAmplitude = speechAmplitude,
        muted = muted,
        silenced = silenced,
        handsFree = handsFree,
        isFullscreen = isFullscreen,
        onMicClick = {
            when (state) {
                AssistantState.IDLE -> service.startRecording()
                AssistantState.LISTENING -> service.currentPersona?.let { service.stopRecording(it) }
                // THINKING/SPEAKING are the Stop button's job; a core tap does not surprise-stop
                // a turn that is already answering.
                else -> Unit
            }
        },
        onStopClick = { service.stopEverything() },
        onMuteToggle = { service.toggleMicMute() },
        onSilenceToggle = { service.toggleSilence() },
        onHandsFreeToggle = {
            if (handsFree) service.stopVadListening() else service.startVadListening()
        },
        onToggleFullscreen = onToggleFullscreen,
        onClose = onClose,
    )
}

@Composable
internal fun LiveHudBody(
    state: AssistantState,
    personaName: String,
    personaColor: Color,
    usage: UsageInfo,
    messages: List<ChatMessage>,
    voiceDuration: Int,
    ttsPlaybackFraction: Float?,
    ttsWordTimestamps: String?,
    micAmplitude: Float,
    speechAmplitude: Float,
    muted: Boolean,
    silenced: Boolean,
    handsFree: Boolean,
    isFullscreen: Boolean,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    onMuteToggle: () -> Unit,
    onSilenceToggle: () -> Unit,
    onHandsFreeToggle: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    val time = remember { mutableFloatStateOf(0f) }
    val level = remember { mutableFloatStateOf(0f) }
    val meter = remember { LevelMeter() }
    val history = remember { LevelHistory(128) }

    // `rememberUpdatedState` is load-bearing: the frame loop is launched once, and without these
    // it would keep reading the state, mic and speech values captured by the first composition.
    val latestState by rememberUpdatedState(state)
    val latestMic by rememberUpdatedState(micAmplitude)
    val latestSpeech by rememberUpdatedState(speechAmplitude)

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000.0).coerceIn(0.0, 0.1)
                last = now
                time.floatValue += dt.toFloat()
                val target = liveHudLevel(latestState, latestMic, latestSpeech, time.floatValue.toDouble())
                val smoothed = meter.next(target, dt.toFloat())
                level.floatValue = smoothed
                history.push(smoothed)
            }
        }
    }

    val accent by animateColorAsState(
        targetValue = liveHudAccent(state, personaColor),
        animationSpec = tween(durationMillis = 650),
        label = "live-hud-accent",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF02040A))
            .testTag("live_hud")
    ) {
        val compact = maxWidth < 900.dp || maxHeight < 640.dp
        val coreSize = (minOf(maxWidth, maxHeight) * (if (compact) 0.56f else 0.64f))
            .coerceIn(180.dp, 760.dp)
        val outerPadding = if (compact) 18.dp else 30.dp

        LiveBackdrop(
            personaColor = personaColor,
            accent = accent,
            time = time,
            level = level,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LiveTopBar(
                state = state,
                personaName = personaName,
                usage = usage,
                accent = accent,
                muted = muted,
                silenced = silenced,
                handsFree = handsFree,
                isFullscreen = isFullscreen,
                compact = compact,
                onToggleFullscreen = onToggleFullscreen,
                onClose = onClose,
            )

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LiveCore(
                    state = state,
                    accent = accent,
                    time = time,
                    level = level,
                    history = history,
                    compact = compact,
                    onTap = {
                        if (state == AssistantState.IDLE || state == AssistantState.LISTENING) {
                            onMicClick()
                        } else {
                            onStopClick()
                        }
                    },
                    modifier = Modifier.size(coreSize),
                )
            }

            LiveTranscript(
                state = state,
                accent = accent,
                personaColor = personaColor,
                messages = messages,
                voiceDuration = voiceDuration,
                ttsPlaybackFraction = ttsPlaybackFraction,
                ttsWordTimestamps = ttsWordTimestamps,
                compact = compact,
                // A fullscreen line of 16 sp text is a wall: the first live run showed the reply
                // spanning the whole 2560 px display. The 960 dp reading column is M17's own, so
                // the HUD reads like the rest of the desktop app rather than like a terminal.
                modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(),
            )

            Spacer(Modifier.height(if (compact) 10.dp else 16.dp))

            LiveControls(
                state = state,
                accent = accent,
                muted = muted,
                silenced = silenced,
                handsFree = handsFree,
                compact = compact,
                onMicClick = onMicClick,
                onStopClick = onStopClick,
                onMuteToggle = onMuteToggle,
                onSilenceToggle = onSilenceToggle,
                onHandsFreeToggle = onHandsFreeToggle,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────── state and level math

/**
 * The colour the core and chrome take in [state].
 *
 * SPEAKING is the one state that must keep the persona's own colour: a reply in a persona's voice
 * should look like that persona. The other states are fixed HUD signals — cyan for listening,
 * amber for computing, slate for standby — so a user can tell what the assistant is doing without
 * reading the label.
 */
internal fun liveHudAccent(state: AssistantState, personaColor: Color): Color = when (state) {
    // Sky rather than the slate this started as: a grey core at rest read as switched off in the
    // first live screenshot, which is the opposite of the "alive but waiting" the HUD is for.
    // The persona still tints the starfield, and SPEAKING hands the core back to it.
    AssistantState.IDLE -> Color(0xFF38BDF8)
    AssistantState.LISTENING -> Color(0xFF22D3EE)
    AssistantState.THINKING -> Color(0xFFF59E0B)
    AssistantState.SPEAKING -> personaColor
}

internal fun liveHudStateLabel(state: AssistantState): String = when (state) {
    AssistantState.IDLE -> "STANDBY"
    AssistantState.LISTENING -> "LISTENING"
    AssistantState.THINKING -> "COMPUTING"
    AssistantState.SPEAKING -> "TRANSMITTING"
}

/**
 * The level the ring should chase at [timeSeconds].
 *
 * LISTENING and SPEAKING are measurements. IDLE and THINKING are intentionally decorative: there
 * is no open microphone to measure in either, and a HUD that moved as if it heard something while
 * it did not would be a lie about the microphone. The SPEAKING fallback is the system-TTS route,
 * which exposes no samples; it is a speech-shaped cadence, not an amplitude claim.
 */
internal fun liveHudLevel(
    state: AssistantState,
    micAmplitude: Float,
    speechAmplitude: Float,
    timeSeconds: Double,
): Float {
    val mic = micAmplitude.coerceIn(0f, 1f)
    val speech = speechAmplitude.coerceIn(0f, 1f)
    return when (state) {
        AssistantState.IDLE ->
            0.08f + 0.04f * sin(timeSeconds * 0.8).toFloat()
        AssistantState.LISTENING -> mic
        AssistantState.THINKING ->
            (0.28f + 0.12f * sin(timeSeconds * 2.4).toFloat() +
                0.10f * sin(timeSeconds * 6.1).toFloat()).coerceIn(0.04f, 0.55f)
        AssistantState.SPEAKING ->
            if (speech > 0.02f) speech else {
                val cadence = (sin(timeSeconds * 9.3) * sin(timeSeconds * 3.1)).toFloat()
                (0.20f + 0.18f * cadence).coerceIn(0.06f, 0.55f)
            }
    }
}

/**
 * Attack/release smoothing for the ring.
 *
 * The VAD publishes a peak per capture chunk, and speech peaks and gaps alternate within tens of
 * milliseconds. A raw level makes the ring strobe; a symmetric filter makes it lag the voice. The
 * two rates are the usual compromise: fast enough that a syllable is visible, slow enough that the
 * gaps between words do not collapse the ring to a point.
 */
internal class LevelMeter(
    private val attackPerSecond: Float = 18f,
    private val releasePerSecond: Float = 4.5f,
) {
    private var current = 0f

    fun next(target: Float, dtSeconds: Float): Float {
        val wanted = target.coerceIn(0f, 1f)
        val dt = dtSeconds.coerceIn(0f, 0.25f)
        val rate = if (wanted >= current) attackPerSecond else releasePerSecond
        val step = rate * dt
        current = if (wanted >= current) {
            minOf(wanted, current + step)
        } else {
            maxOf(wanted, current - step)
        }
        return current
    }
}

/** The last [size] levels, newest first, for the travelling waveform ring. */
internal class LevelHistory(private val size: Int) {
    private val values = FloatArray(size)
    private var head = 0

    fun push(value: Float) {
        values[head] = value
        head = (head + 1) % size
    }

    /** The value [age] frames ago, where 0 is the most recently pushed. */
    fun at(age: Int): Float = values[((head - 1 - age) % size + size) % size]
}

// ─────────────────────────────────────────────────────────────── chrome

@Composable
private fun LiveTopBar(
    state: AssistantState,
    personaName: String,
    usage: UsageInfo,
    accent: Color,
    muted: Boolean,
    silenced: Boolean,
    handsFree: Boolean,
    isFullscreen: Boolean,
    compact: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = LocalTime.now()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "◆ ${personaName.take(if (compact) 14 else 28).uppercase()}",
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 10.sp else 12.sp,
                letterSpacing = 3.sp,
                color = accent.copy(alpha = 0.95f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(liveHudStateLabel(state))
                    if (handsFree) append(" · HANDS-FREE")
                    if (muted) append(" · MUTED")
                    if (silenced) append(" · SILENT")
                },
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 8.sp else 9.sp,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = now.format(HUD_TIME),
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 10.sp else 12.sp,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                )
                Text(
                    text = if (usage.totalTokens > 0) "TOK ${usage.totalTokens}" else "STANDBY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                )
            }
            Spacer(Modifier.width(if (compact) 10.dp else 18.dp))
            LiveIconButton(
                icon = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                description = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                tint = accent,
                size = if (compact) 38.dp else 44.dp,
                onClick = onToggleFullscreen,
            )
            Spacer(Modifier.width(8.dp))
            LiveIconButton(
                icon = Icons.Default.Close,
                description = "Close Live HUD",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                size = if (compact) 38.dp else 44.dp,
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun LiveControls(
    state: AssistantState,
    accent: Color,
    muted: Boolean,
    silenced: Boolean,
    handsFree: Boolean,
    compact: Boolean,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    onMuteToggle: () -> Unit,
    onSilenceToggle: () -> Unit,
    onHandsFreeToggle: () -> Unit,
) {
    val iconSize = if (compact) 40.dp else 48.dp
    val active = state == AssistantState.LISTENING ||
        state == AssistantState.THINKING ||
        state == AssistantState.SPEAKING

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveIconButton(
            icon = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
            description = if (muted) "Unmute microphone" else "Mute microphone",
            tint = accent,
            active = muted,
            size = iconSize,
            onClick = onMuteToggle,
        )
        LiveIconButton(
            icon = Icons.Default.Hearing,
            description = if (handsFree) "Hands-free on" else "Hands-free off",
            tint = accent,
            active = handsFree,
            size = iconSize,
            onClick = onHandsFreeToggle,
        )
        LivePrimaryButton(
            active = active,
            tint = accent,
            size = if (compact) 54.dp else 64.dp,
            onClick = if (active) onStopClick else onMicClick,
        )
        LiveIconButton(
            icon = if (silenced) Icons.AutoMirrored.Filled.VolumeOff
            else Icons.AutoMirrored.Filled.VolumeUp,
            description = if (silenced) "Enable speaking" else "Silence replies",
            tint = accent,
            active = silenced,
            size = iconSize,
            onClick = onSilenceToggle,
        )
        // A deliberate empty slot: the classic screen's fifth control is text mode, and the Live
        // HUD is voice-first. Keeping the primary button geometrically centred matters more than
        // filling the row.
        Spacer(Modifier.size(iconSize))
    }
}

@Composable
private fun LiveIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    size: Dp,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(tint.copy(alpha = if (active) 0.16f else 0.05f))
            .border(1.dp, tint.copy(alpha = if (active) 0.55f else 0.18f), shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint.copy(alpha = if (active) 1f else 0.65f),
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

@Composable
private fun LivePrimaryButton(
    active: Boolean,
    tint: Color,
    size: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    listOf(tint.copy(alpha = 0.34f), tint.copy(alpha = 0.06f)),
                ),
                shape = CircleShape,
            )
            .border(1.dp, tint.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (active) "Stop" else "Start listening" },
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(size * 0.30f)
                    .background(tint, RoundedCornerShape(4.dp))
            )
        } else {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.38f),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────── transcript

@Composable
private fun LiveTranscript(
    state: AssistantState,
    accent: Color,
    personaColor: Color,
    messages: List<ChatMessage>,
    voiceDuration: Int,
    ttsPlaybackFraction: Float?,
    ttsWordTimestamps: String?,
    compact: Boolean,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    val lastAssistant = messages.lastOrNull { it.role == "assistant" && !it.isError }
    val lastUser = messages.lastOrNull { it.role == "user" }
    val speaking = state == AssistantState.SPEAKING

    // Same split as the Celestial panel: arriving content jumps to the newest text, but while the
    // player is driving a highlight the progress effect owns the scroll. Two owners fighting for
    // the same scrollbar is how the spoken word ends up off-screen.
    LaunchedEffect(messages.size, state) {
        if (speaking && ttsPlaybackFraction != null) return@LaunchedEffect
        if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue)
    }
    LaunchedEffect(ttsPlaybackFraction, speaking) {
        val fraction = ttsPlaybackFraction ?: return@LaunchedEffect
        if (!speaking || scroll.isScrollInProgress) return@LaunchedEffect
        if (scroll.maxValue > 0) scroll.scrollTo((fraction * scroll.maxValue).roundToInt())
    }

    val activeRange = if (speaking && lastAssistant != null && ttsPlaybackFraction != null) {
        ttsActiveWordRange(
            lastAssistant.text,
            ttsWordTimestamps,
            ttsPlaybackFraction,
            voiceDuration,
        )
    } else null

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lastUser != null) {
            Text(
                text = "YOU · ${lastUser.text.replace('\n', ' ')}",
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 8.sp else 9.sp,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = if (compact) 110.dp else 170.dp)
                .verticalScroll(scroll),
        ) {
            when {
                lastAssistant != null && lastAssistant.text.isNotBlank() -> Text(
                    text = highlightedAnnotated(lastAssistant.text, activeRange, accent),
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 13.sp else 16.sp,
                    lineHeight = if (compact) 19.sp else 24.sp,
                    color = if (state == AssistantState.IDLE) {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.95f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                state == AssistantState.THINKING -> Text(
                    text = "COMPUTING …",
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 13.sp else 15.sp,
                    letterSpacing = 3.sp,
                    color = personaColor.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                state == AssistantState.LISTENING -> Text(
                    text = "LISTENING — SPEAK NOW",
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 11.sp else 13.sp,
                    letterSpacing = 3.sp,
                    color = accent.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                else -> Text(
                    text = "STANDBY — AWAITING INPUT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 11.sp else 13.sp,
                    letterSpacing = 3.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────── drawing

@Composable
private fun LiveBackdrop(
    personaColor: Color,
    accent: Color,
    time: State<Float>,
    level: State<Float>,
    modifier: Modifier,
) {
    val stars = remember {
        val rng = kotlin.random.Random(20)
        List(160) { Triple(rng.nextFloat(), rng.nextFloat(), rng.nextFloat()) }
    }
    Canvas(modifier = modifier) {
        val t = time.value
        val lvl = level.value
        drawRect(Brush.verticalGradient(listOf(Color(0xFF07111F), Color(0xFF01030A))))
        drawRect(
            Brush.radialGradient(
                colors = listOf(
                    personaColor.copy(alpha = 0.12f + 0.05f * lvl),
                    Color.Transparent,
                ),
                center = center,
                radius = size.minDimension * 0.75f,
            )
        )

        stars.forEach { (x, y, phase) ->
            val twinkle = 0.15f + 0.45f * (
                0.5f + 0.5f * sin((phase * 10f + t * 0.6f) * 2f * PI.toFloat())
                )
            val reaction = (lvl * 0.35f)
            drawCircle(
                color = if (phase > 0.86f) {
                    personaColor.copy(alpha = (twinkle + reaction).coerceAtMost(1f))
                } else {
                    Color.White.copy(alpha = (twinkle + reaction).coerceAtMost(1f))
                },
                radius = if (phase > 0.93f) 2.0f else 1.2f,
                center = Offset(x * size.width, y * size.height),
            )
        }

        // A horizon grid in the lower third: enough to read as a HUD surface, faint enough not to
        // compete with the transcript. Drawn as lines rather than a bitmap so it scales with the
        // window and costs nothing to keep crisp.
        for (i in 0..24) {
            val y = size.height * (0.66f + i * 0.014f)
            drawLine(
                color = accent.copy(alpha = 0.035f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }
        for (i in 1..12) {
            val x = size.width * i / 13f
            drawLine(
                color = accent.copy(alpha = 0.022f),
                start = Offset(x, size.height * 0.66f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
        }

        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = center,
                radius = size.minDimension * 0.78f,
            )
        )
    }
}

@Composable
private fun LiveCore(
    state: AssistantState,
    accent: Color,
    time: State<Float>,
    level: State<Float>,
    history: LevelHistory,
    compact: Boolean,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clickable(onClick = onTap)
            .semantics { contentDescription = "Live HUD core" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = time.value
            val lvl = level.value
            val c = center
            val outerR = size.minDimension / 2f - 6f
            val speaking = state == AssistantState.SPEAKING
            val listening = state == AssistantState.LISTENING
            val thinking = state == AssistantState.THINKING

            val outerSpeed = when (state) {
                AssistantState.IDLE -> 4f
                AssistantState.LISTENING -> 16f
                AssistantState.THINKING -> 55f
                AssistantState.SPEAKING -> 22f
            } + lvl * 24f
            val innerSpeed = outerSpeed * 0.55f

            // Ambient glow first, so every ring sits inside it.
            val glowR = outerR * (1.05f + 0.08f * lvl)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.22f + 0.22f * lvl),
                        accent.copy(alpha = 0.08f),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = glowR,
                ),
                radius = glowR,
                center = c,
            )

            // Outer segmented orbit.
            rotate(t * outerSpeed) {
                for (i in 0..2) {
                    drawArc(
                        color = accent.copy(alpha = 0.32f),
                        startAngle = i * 120f,
                        sweepAngle = 86f,
                        useCenter = false,
                        topLeft = Offset(c.x - outerR, c.y - outerR),
                        size = Size(outerR * 2f, outerR * 2f),
                        style = Stroke(width = 2f),
                    )
                }
                drawCircle(
                    color = accent.copy(alpha = 0.16f),
                    radius = outerR,
                    center = c,
                    style = Stroke(
                        width = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 12f)),
                    ),
                )
            }

            // Tick ring, counter-rotating, every sixth tick long.
            rotate(-t * innerSpeed) {
                for (i in 0 until 72) {
                    val long = i % 6 == 0
                    rotate(i * 5f) {
                        val start = Offset(c.x, c.y - outerR * 0.90f)
                        drawLine(
                            color = accent.copy(alpha = if (long) 0.55f else 0.22f),
                            start = start,
                            end = Offset(start.x, start.y - if (long) 10f else 4f),
                            strokeWidth = if (long) 2f else 1f,
                        )
                    }
                }
            }

            drawCircle(
                color = accent.copy(alpha = 0.18f),
                radius = outerR * 0.62f,
                center = c,
                style = Stroke(
                    width = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 16f)),
                ),
            )

            // Travelling waveform: one bar per history sample, rotated so the newest edges lead.
            val segments = 96
            val baseR = outerR * 0.78f
            for (i in 0 until segments) {
                val v = history.at(i)
                val angle = (i.toFloat() / segments) * 360f + t * (10f + lvl * 46f)
                rotate(angle) {
                    drawLine(
                        color = accent.copy(alpha = 0.16f + 0.68f * v),
                        start = Offset(c.x, c.y - baseR),
                        end = Offset(c.x, c.y - baseR - (4f + v * outerR * 0.16f)),
                        strokeWidth = if (compact) 2f else 2.5f,
                    )
                }
            }

            if (thinking) {
                rotate(t * 90f) {
                    drawArc(
                        color = accent.copy(alpha = 0.75f),
                        startAngle = 0f,
                        sweepAngle = 42f,
                        useCenter = false,
                        topLeft = Offset(c.x - outerR * 0.94f, c.y - outerR * 0.94f),
                        size = Size(outerR * 1.88f, outerR * 1.88f),
                        style = Stroke(width = 3f),
                    )
                }
            }

            if (speaking) {
                for (p in 0..2) {
                    val phase = ((t * 0.7f + p / 3f) % 1f)
                    drawCircle(
                        color = accent.copy(alpha = (1f - phase) * 0.28f * (0.4f + lvl)),
                        radius = outerR * (0.35f + phase * 0.60f),
                        center = c,
                        style = Stroke(width = 2f),
                    )
                }
            }

            // Central orb: a lit sphere, not a flat disc — the offset highlight is what makes it
            // read as volume at a glance.
            val breathe = 1f + 0.015f * sin(t * (if (listening) 3.2f else 1.6f))
            val orbR = outerR * 0.30f * (breathe + lvl * 0.10f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lerpColor(accent, Color.White, 0.72f),
                        accent,
                        darken(accent, 0.35f),
                    ),
                    center = Offset(c.x - orbR * 0.30f, c.y - orbR * 0.36f),
                    radius = orbR * 1.5f,
                ),
                radius = orbR,
                center = c,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.50f),
                radius = orbR * 0.12f,
                center = Offset(c.x - orbR * 0.32f, c.y - orbR * 0.36f),
            )
            drawCircle(
                color = accent.copy(alpha = 0.55f),
                radius = orbR * 1.18f,
                center = c,
                style = Stroke(width = 1.5f),
            )

            // Cardinal alignment ticks.
            listOf(0f, 90f, 180f, 270f).forEach { deg ->
                rotate(deg) {
                    drawLine(
                        color = accent.copy(alpha = 0.35f),
                        start = Offset(c.x, c.y - outerR - 8f),
                        end = Offset(c.x, c.y - outerR - 16f),
                        strokeWidth = 2f,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = liveHudStateLabel(state),
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 8.sp else 10.sp,
                letterSpacing = 3.sp,
                color = accent.copy(alpha = 0.80f),
            )
            if (state == AssistantState.IDLE) {
                Text(
                    text = "TAP CORE TO TALK",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────── small helpers

private val HUD_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

private fun darken(color: Color, factor: Float): Color = Color(
    red = color.red * factor,
    green = color.green * factor,
    blue = color.blue * factor,
    alpha = color.alpha,
)
