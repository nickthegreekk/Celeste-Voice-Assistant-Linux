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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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
    isFocused: Boolean,
    isMinimized: Boolean,
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
        isFocused = isFocused,
        isMinimized = isMinimized,
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
    isFocused: Boolean = true,
    isMinimized: Boolean = false,
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
    val latestFocused by rememberUpdatedState(isFocused)
    val latestMinimized by rememberUpdatedState(isMinimized)

    // A fixed-rate loop rather than `withFrameNanos`. Continuous frame-clock updates make a
    // full-screen canvas render as fast as the compositor accepts; the lattice and waveform do
    // not need display-rate updates, and a background HUD should not compete with the foreground
    // app. 30 fps focused and 10 fps unfocused is deliberately below the compositor's rate.
    // Minimised means no time and no draws at all.
    LaunchedEffect(Unit) {
        var lastNanos = System.nanoTime()
        while (true) {
            if (latestMinimized) {
                lastNanos = System.nanoTime()
                delay(250)
                continue
            }
            delay(if (latestFocused) 33L else 100L)
            val now = System.nanoTime()
            val dt = ((now - lastNanos) / 1_000_000_000.0).coerceIn(0.0, 0.1)
            lastNanos = now
            time.floatValue += dt.toFloat()
            val target = liveHudLevel(latestState, latestMic, latestSpeech, time.floatValue.toDouble())
            val smoothed = meter.next(target, dt.toFloat())
            level.floatValue = smoothed
            history.push(smoothed)
        }
    }

    val accent by animateColorAsState(
        targetValue = liveHudAccent(state),
        animationSpec = tween(durationMillis = 650),
        label = "live-hud-accent",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0402))
            .testTag("live_hud")
    ) {
        val compact = maxWidth < 900.dp || maxHeight < 640.dp
        val coreSize = (minOf(maxWidth, maxHeight) * (if (compact) 0.58f else 0.66f))
            .coerceIn(180.dp, 780.dp)
        val outerPadding = if (compact) 18.dp else 30.dp

        LiveBackdrop(
            accent = accent,
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
 * The warm hologram palette, per state.
 *
 * The reference the user chose is the amber JARVIS reactor, not a cool cyan interface. The Live
 * HUD is therefore deliberately **not** persona-tinted: it is one warm material whose temperature
 * changes with what the assistant is doing. The Celestial screen keeps the persona's colour; this
 * window is the fixed cinematic theme.
 */
internal fun liveHudAccent(state: AssistantState): Color = when (state) {
    AssistantState.IDLE -> Color(0xFFB45309)      // banked embers
    AssistantState.LISTENING -> Color(0xFFF59E0B) // amber, listening
    AssistantState.THINKING -> Color(0xFFFB923C)  // orange, computing
    AssistantState.SPEAKING -> Color(0xFFFFC46B)  // gold, transmitting
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
                color = HUD_GOLD.copy(alpha = 0.55f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = now.format(HUD_TIME),
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 10.sp else 12.sp,
                    letterSpacing = 2.sp,
                    color = HUD_GOLD.copy(alpha = 0.75f),
                )
                Text(
                    text = if (usage.totalTokens > 0) "TOK ${usage.totalTokens}" else "STANDBY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 1.5.sp,
                    color = HUD_GOLD.copy(alpha = 0.35f),
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
                tint = HUD_GOLD.copy(alpha = 0.65f),
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
                color = HUD_GOLD.copy(alpha = 0.32f),
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
                        HUD_HOT.copy(alpha = 0.50f)
                    } else {
                        HUD_HOT.copy(alpha = 0.95f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                state == AssistantState.THINKING -> Text(
                    text = "COMPUTING …",
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 13.sp else 15.sp,
                    letterSpacing = 3.sp,
                    color = accent.copy(alpha = 0.7f),
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
                    color = HUD_GOLD.copy(alpha = 0.28f),
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
    accent: Color,
    modifier: Modifier,
) {
    val stars = remember {
        val rng = kotlin.random.Random(20)
        List(120) { Triple(rng.nextFloat(), rng.nextFloat(), rng.nextFloat()) }
    }
    Canvas(modifier = modifier) {
        // Static on purpose. The first version twinkled every star and read the level on every
        // frame, which made this full-screen layer a per-frame redraw of the whole window. The
        // backdrop is scenery; the neural core is the thing that moves.
        drawRect(Brush.verticalGradient(listOf(Color(0xFF220D05), Color(0xFF070201))))
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0xFFFF6A00).copy(alpha = 0.20f), Color.Transparent),
                center = center,
                radius = size.minDimension * 0.80f,
            )
        )
        // A few large, almost invisible warm clouds: the reference's background is hazy rather
        // than a clean black field. Static, so they cost one draw each time the accent changes.
        listOf(
            Triple(0.24f, 0.30f, 0.30f),
            Triple(0.76f, 0.62f, 0.34f),
            Triple(0.62f, 0.20f, 0.22f),
            Triple(0.14f, 0.74f, 0.26f),
        ).forEach { (x, y, fraction) ->
            val cloudR = size.minDimension * fraction
            val centre = Offset(x * size.width, y * size.height)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFF7A1A).copy(alpha = 0.05f), Color.Transparent),
                    center = centre,
                    radius = cloudR,
                ),
                radius = cloudR,
                center = centre,
            )
        }

        stars.forEach { (x, y, phase) ->
            val twinkle = 0.10f + 0.36f * (0.5f + 0.5f * sin(phase * 10f))
            drawCircle(
                color = if (phase > 0.86f) {
                    Color(0xFFFFD9A0).copy(alpha = twinkle)
                } else {
                    Color.White.copy(alpha = twinkle * 0.7f)
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
                color = accent.copy(alpha = 0.045f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }
        for (i in 1..12) {
            val x = size.width * i / 13f
            drawLine(
                color = accent.copy(alpha = 0.028f),
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
    // One geometry for the life of the window: 160 points on a Fibonacci sphere and the short
    // chords between them. Built once because the cost is O(n²) and the shape never changes; the
    // motion comes from rotating and projecting it, not from rebuilding it. 160 is the density
    // the reference image needs: enough lines to read as a solid hologram at a glance, still a
    // single draw call per edge at 30 fps.
    val geometry = remember { NeuralGeometry.build(160) }

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
            val sphereR = outerR * 0.52f * (1f + 0.06f * lvl)
            val lineWidth = maxOf(1f, outerR * if (compact) 0.004f else 0.003f)
            val thinking = state == AssistantState.THINKING

            // A soft aura behind the lattice, not a solid planet.
            val auraR = sphereR * 2.1f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.10f + 0.16f * lvl),
                        accent.copy(alpha = 0.035f),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = auraR,
                ),
                radius = auraR,
                center = c,
            )

            // The lattice is embedded in a soft translucent sphere, so it reads as a volume
            // rather than a hollow wire cage. This is the reference's warm inner bloom.
            val bloomR = sphereR * 1.20f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        HUD_GOLD.copy(alpha = 0.10f + 0.09f * lvl),
                        accent.copy(alpha = 0.04f),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = bloomR,
                ),
                radius = bloomR,
                center = c,
            )

            // ── hologram rings and glyphs ───────────────────────────────────────────────────
            // The reference has concentric rings, but they are framing rather than the subject:
            // thin, broken and almost static. Keeping them faint is what stops the core reading
            // as a spinning wheel; the lattice, spokes and particles carry the motion.
            val ringRadii = floatArrayOf(0.98f, 0.84f, 0.66f)
            ringRadii.forEachIndexed { ringIndex, fraction ->
                val ringR = outerR * fraction
                drawCircle(
                    color = accent.copy(alpha = 0.10f + ringIndex * 0.015f),
                    radius = ringR,
                    center = c,
                    style = Stroke(width = lineWidth * 0.7f),
                )
                val ringSpin = if (ringIndex % 2 == 0) 1.4f else -1.1f
                rotate(t * ringSpin) {
                    for (i in 0 until 48) {
                        if (i % 3 != 0) continue
                        rotate(i * (360f / 48f) + ringIndex * 7f) {
                            val tick = if (i % 6 == 0) outerR * 0.022f else outerR * 0.010f
                            drawLine(
                                color = accent.copy(alpha = 0.16f + 0.10f * lvl),
                                start = Offset(c.x, c.y - ringR),
                                end = Offset(c.x, c.y - ringR - tick),
                                strokeWidth = lineWidth * 0.7f,
                            )
                        }
                    }
                    for (i in 0 until 12) {
                        rotate(i * 30f + ringIndex * 15f) {
                            drawRect(
                                color = if (i % 3 == 0) {
                                    HUD_GOLD.copy(alpha = 0.28f)
                                } else {
                                    accent.copy(alpha = 0.14f)
                                },
                                topLeft = Offset(
                                    c.x - outerR * 0.012f,
                                    c.y - ringR - outerR * 0.006f,
                                ),
                                size = Size(outerR * 0.024f, outerR * 0.012f),
                            )
                        }
                    }
                }
            }

            // Radial energy spokes: the bright streaks in the reference are what make the core
            // read as powered rather than merely drawn.
            val spokes = if (compact) 28 else 40
            for (k in 0 until spokes) {
                val angle = (k.toFloat() / spokes) * 360f + t * 5f
                rotate(angle) {
                    drawLine(
                        color = HUD_GOLD.copy(alpha = 0.035f + 0.08f * lvl),
                        start = Offset(c.x + outerR * 0.10f, c.y),
                        end = Offset(c.x + outerR * (0.60f + 0.04f * sin(k * 1.7f)), c.y),
                        strokeWidth = lineWidth * 0.6f,
                    )
                }
            }

            // ── neural lattice ──────────────────────────────────────────────────────────────
            // Rotate on Y, tilt on X, project with a gentle perspective. A point's depth drives
            // its size and brightness, which is what makes the sphere read as a volume rather
            // than as a rotating ring.
            val spin = when (state) {
                AssistantState.IDLE -> 0.18f
                AssistantState.LISTENING -> 0.34f
                AssistantState.THINKING -> 0.85f
                AssistantState.SPEAKING -> 0.48f
            } + lvl * 0.55f
            val rotY = t * spin
            val rotX = sin(t * 0.17f) * 0.30f
            val cosY = cos(rotY)
            val sinY = sin(rotY)
            val cosX = cos(rotX)
            val sinX = sin(rotX)

            val projected = geometry.points.mapIndexed { index, p ->
                val x1 = p.x * cosY + p.z * sinY
                val z1 = -p.x * sinY + p.z * cosY
                val y2 = p.y * cosX - z1 * sinX
                val z2 = p.y * sinX + z1 * cosX
                // Audio makes the lattice breathe irregularly rather than scale as one rigid ball.
                val displacement = 1f + lvl * 0.09f * sin(t * 2.4f + index * 0.73f)
                val px = x1 * sphereR * displacement
                val py = y2 * sphereR * displacement
                val perspective = 2.6f / (2.6f + z2)
                ProjectedSpherePoint(
                    position = Offset(c.x + px * perspective, c.y + py * perspective),
                    // z2 = -1 is nearest the viewer; z2 = +1 is furthest.
                    depth = ((-z2) + 1f) / 2f,
                )
            }

            geometry.edges.forEach { (a, b) ->
                val pa = projected[a]
                val pb = projected[b]
                val depth = (pa.depth + pb.depth) / 2f
                drawLine(
                    color = accent.copy(alpha = 0.025f + 0.20f * depth + 0.08f * lvl),
                    start = pa.position,
                    end = pb.position,
                    strokeWidth = lineWidth * (0.65f + depth * 0.7f),
                )
            }

            // Pulses travelling along a stable subset of the edges: the "signals" in the lattice.
            // They are the one element that reads as computation rather than decoration.
            val edgeCount = geometry.edges.size
            if (edgeCount > 0) {
                val pulseCount = if (compact) 16 else 26
                for (k in 0 until pulseCount) {
                    val (a, b) = geometry.edges[(k * 37 + 11) % edgeCount]
                    val pa = projected[a]
                    val pb = projected[b]
                    val speed = 0.16f + (k % 5) * 0.045f + lvl * 0.55f
                    val u = ((t * speed + k * 0.618f) % 1f)
                    val x = pa.position.x + (pb.position.x - pa.position.x) * u
                    val y = pa.position.y + (pb.position.y - pa.position.y) * u
                    drawCircle(
                        color = accent.copy(alpha = (0.24f + 0.55f * (pa.depth + pb.depth) / 2f) * (0.5f + lvl)),
                        radius = maxOf(1.2f, outerR * 0.005f),
                        center = Offset(x, y),
                    )
                }
            }

            projected.forEachIndexed { index, p ->
                val wave = if (thinking) {
                    0.55f + 0.45f * sin(t * 4.2f - index * 0.35f)
                } else 1f
                val nodeR = maxOf(1f, outerR * 0.0042f * (0.55f + p.depth * 1.35f))
                drawCircle(
                    color = HUD_HOT.copy(alpha = (0.16f + 0.62f * p.depth) * wave),
                    radius = nodeR,
                    center = p.position,
                )
                if (p.depth > 0.68f) {
                    drawCircle(
                        color = accent.copy(alpha = 0.20f * p.depth * wave),
                        radius = nodeR * 2.6f,
                        center = p.position,
                    )
                }
            }

            // ── warm particles ──────────────────────────────────────────────────────────────
            // Slow embers orbiting the lattice. Their brightness follows the level so the whole
            // field leans toward the voice without moving as a rigid wheel.
            val particles = if (compact) 36 else 64
            for (k in 0 until particles) {
                val base = k * 2.399963f
                val direction = if (k % 2 == 0) 1f else -1f
                val angle = base + t * (0.10f + (k % 7) * 0.02f) * direction
                val radius = outerR * (0.58f + 0.34f * ((k % 11) / 11f))
                val wobble = 1f + 0.02f * sin(t * 1.3f + k)
                val x = c.x + cos(angle) * radius * wobble
                val y = c.y + sin(angle) * radius * wobble * 0.92f
                val bright = 0.14f + 0.42f * lvl + 0.10f * (0.5f + 0.5f * sin(t * 2.1f + k))
                drawCircle(
                    color = if (k % 5 == 0) {
                        HUD_HOT.copy(alpha = bright)
                    } else {
                        HUD_GOLD.copy(alpha = bright * 0.8f)
                    },
                    radius = maxOf(1f, outerR * if (k % 9 == 0) 0.007f else 0.004f),
                    center = Offset(x, y),
                )
            }

            // ── filaments ───────────────────────────────────────────────────────────────────
            // A handful of energy tendrils leave the singularity and end on the lattice. Their
            // control points drift on different periods, so they never line up into a spoke.
            val filamentCount = if (compact) 7 else 10
            for (k in 0 until filamentCount) {
                val idx = (k * 17 + 5) % projected.size
                val target = projected[idx]
                val mid = Offset(
                    (c.x + target.position.x) / 2f + sin(t * 1.7f + k) * sphereR * 0.16f,
                    (c.y + target.position.y) / 2f + cos(t * 1.3f + k * 0.7f) * sphereR * 0.16f,
                )
                val path = Path().apply {
                    moveTo(c.x, c.y)
                    quadraticTo(mid.x, mid.y, target.position.x, target.position.y)
                }
                drawPath(
                    path = path,
                    color = HUD_GOLD.copy(alpha = 0.08f + 0.26f * lvl + 0.06f * (0.5f + 0.5f * sin(t + k))),
                    style = Stroke(width = lineWidth * (0.8f + lvl * 1.4f)),
                )
            }

            // ── scan plane ──────────────────────────────────────────────────────────────────
            // A thin horizontal sweep through the lattice. Faint at rest, brighter while the
            // assistant is thinking, which gives COMPUTING a direction without another spinner.
            val scanY = c.y + sin(t * 0.45f) * sphereR * 0.85f
            val scanHalf = sqrt(
                (sphereR * sphereR - (scanY - c.y) * (scanY - c.y)).coerceAtLeast(0f)
            ) * 0.92f
            val scanAlpha = when (state) {
                AssistantState.THINKING -> 0.30f
                AssistantState.LISTENING -> 0.16f
                AssistantState.SPEAKING -> 0.10f
                AssistantState.IDLE -> 0.06f
            }
            drawLine(
                color = accent.copy(alpha = scanAlpha),
                start = Offset(c.x - scanHalf, scanY),
                end = Offset(c.x + scanHalf, scanY),
                strokeWidth = lineWidth,
            )
            drawLine(
                color = accent.copy(alpha = scanAlpha * 0.28f),
                start = Offset(c.x - scanHalf, scanY - outerR * 0.012f),
                end = Offset(c.x + scanHalf, scanY - outerR * 0.012f),
                strokeWidth = lineWidth * 3f,
            )

            // ── equator ─────────────────────────────────────────────────────────────────────
            // The bright horizontal line across the reactor is the strongest single signature in
            // the reference image. It is drawn over the lattice, under the core.
            val equatorHalf = outerR * 1.02f
            drawLine(
                color = HUD_GOLD.copy(alpha = 0.05f + 0.07f * lvl),
                start = Offset(c.x - equatorHalf, c.y),
                end = Offset(c.x + equatorHalf, c.y),
                strokeWidth = lineWidth * 9f,
            )
            drawLine(
                color = HUD_HOT.copy(alpha = 0.30f + 0.24f * lvl),
                start = Offset(c.x - equatorHalf, c.y),
                end = Offset(c.x + equatorHalf, c.y),
                strokeWidth = lineWidth * 0.8f,
            )
            drawCircle(
                color = HUD_HOT.copy(alpha = 0.70f),
                radius = lineWidth * 1.8f,
                center = Offset(c.x - equatorHalf, c.y),
            )
            drawCircle(
                color = HUD_HOT.copy(alpha = 0.70f),
                radius = lineWidth * 1.8f,
                center = Offset(c.x + equatorHalf, c.y),
            )

            // ── singularity ─────────────────────────────────────────────────────────────────
            // The one solid form: a small intense core, not the old planet-sized orb.
            val beat = 1f + 0.16f * lvl + 0.025f * sin(t * 3.1f)
            val coreR = outerR * 0.075f * beat
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        HUD_HOT.copy(alpha = 0.95f),
                        HUD_GOLD.copy(alpha = 0.82f),
                        accent.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = coreR * 6.5f,
                ),
                radius = coreR * 6.5f,
                center = c,
            )
            drawCircle(color = HUD_HOT.copy(alpha = 0.92f), radius = coreR * 0.55f, center = c)

            // A slow hexagonal aperture around the core, part of the interface rather than a ring.
            val apertureR = coreR * 2.3f
            val aperture = Path()
            for (i in 0..6) {
                val angle = t * 0.35f + i * (PI.toFloat() * 2f / 6f)
                val x = c.x + cos(angle) * apertureR
                val y = c.y + sin(angle) * apertureR
                if (i == 0) aperture.moveTo(x, y) else aperture.lineTo(x, y)
            }
            drawPath(
                path = aperture,
                color = HUD_GOLD.copy(alpha = 0.30f + 0.20f * lvl),
                style = Stroke(width = lineWidth),
            )

            // Four small brackets at the diagonal corners, the HUD's frame rather than an orbit.
            val inset = outerR * 0.035f
            val bracket = outerR * 0.10f
            val cornerColor = accent.copy(alpha = 0.20f)
            fun bracketAt(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(cornerColor, Offset(x, y), Offset(x + dx, y), lineWidth)
                drawLine(cornerColor, Offset(x, y), Offset(x, y + dy), lineWidth)
            }
            bracketAt(inset, inset, bracket, bracket)
            bracketAt(size.width - inset, inset, -bracket, bracket)
            bracketAt(inset, size.height - inset, bracket, -bracket)
            bracketAt(size.width - inset, size.height - inset, -bracket, -bracket)

            // ── voice waveform ──────────────────────────────────────────────────────────────
            // The old version put the level history on a circular ring, which is what made the
            // whole thing read as a spinning wheel. A horizontal trace is the form a voice
            // visualiser is actually recognised by, and it leaves the lattice to be the "AI".
            if (state != AssistantState.THINKING) {
                val samples = 64
                val waveY = size.height * 0.88f
                val halfWidth = sphereR * 1.15f
                val path = Path()
                for (i in 0 until samples) {
                    // Two history frames per sample: the 128-frame buffer covers the trace.
                    val v = history.at(i * 2)
                    val x = c.x - halfWidth + (i / (samples - 1f)) * halfWidth * 2f
                    val y = waveY - v * sphereR * 0.15f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = HUD_GOLD.copy(alpha = 0.10f),
                    style = Stroke(width = lineWidth * 4f),
                )
                drawPath(
                    path = path,
                    color = HUD_GOLD.copy(alpha = 0.58f),
                    style = Stroke(width = lineWidth * 1.2f),
                )
            }

            // Sparse alignment ticks, four groups only. A full ring of 72 was the wheel.
            listOf(45f, 135f, 225f, 315f).forEach { deg ->
                rotate(deg) {
                    drawLine(
                        color = accent.copy(alpha = 0.24f),
                        start = Offset(c.x, c.y - outerR * 0.96f),
                        end = Offset(c.x, c.y - outerR * 0.96f - outerR * 0.035f),
                        strokeWidth = lineWidth,
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
                    color = HUD_GOLD.copy(alpha = 0.28f),
                )
            }
        }
    }
}

/** One point on the unit sphere, before rotation and projection. */
private data class SpherePoint(val x: Float, val y: Float, val z: Float)

/** A projected lattice point and its 0..1 depth (1 = nearest the viewer). */
private data class ProjectedSpherePoint(val position: Offset, val depth: Float)

/**
 * The Fibonacci-sphere lattice: an even distribution of points and the short chords between them.
 *
 * Built once per window. The edges are the expensive half and never change; only their projected
 * endpoints do. Distances are compared squared, and the threshold is a chord of ~0.57 units — about
 * ten neighbours per point, which is dense enough to read as a mesh and sparse enough to stay
 * under a thousand lines at 30 fps.
 */
private class NeuralGeometry(
    val points: List<SpherePoint>,
    val edges: List<Pair<Int, Int>>,
) {
    companion object {
        fun build(count: Int): NeuralGeometry {
            require(count >= 8) { "a lattice needs at least 8 points" }
            val golden = PI * (3.0 - sqrt(5.0))
            val points = List(count) { i ->
                val y = 1.0 - (i / (count - 1.0)) * 2.0
                val radius = sqrt((1.0 - y * y).coerceAtLeast(0.0))
                val theta = golden * i
                SpherePoint(
                    x = (cos(theta) * radius).toFloat(),
                    y = y.toFloat(),
                    z = (sin(theta) * radius).toFloat(),
                )
            }
            val threshold = 0.34f
            val edges = ArrayList<Pair<Int, Int>>()
            for (i in points.indices) {
                for (j in i + 1 until points.size) {
                    val dx = points[i].x - points[j].x
                    val dy = points[i].y - points[j].y
                    val dz = points[i].z - points[j].z
                    if (dx * dx + dy * dy + dz * dz < threshold) edges += i to j
                }
            }
            return NeuralGeometry(points, edges)
        }
    }
}

// ─────────────────────────────────────────────────────────────── small helpers

/** The hot centre of the reactor: white-gold, used sparingly. */
private val HUD_HOT = Color(0xFFFFF3D6)

/** The warm metal the rings, glyphs and filaments are drawn in. */
private val HUD_GOLD = Color(0xFFFFC46B)

private val HUD_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
