package com.nikosm.voiceassistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.skiaCanvas
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
import kotlin.math.cos
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
 * (clock, persona, state, usage), and its visuals are built around a live nebula rather than a
 * planet. Keeping it separate means neither screen has to grow a mode flag, and the Celestial
 * toggle in Settings keeps meaning exactly what it meant before.
 *
 * ## What drives the motion
 *
 * One frame loop owns time, the level and a short level history. Time drives the cloud's drift
 * and turbulence; the level is smoothed with separate attack and release so a syllable is visible
 * without a silent gap collapsing the cloud. The loop writes Compose state that the draw lambdas
 * read, so the per-frame cost is a redraw of the canvases rather than a recomposition of the
 * transcript and controls. When [state] is IDLE or THINKING there is no input to measure, and the
 * level is a deliberately small decorative cadence — never presented as microphone activity.
 *
 * ## Colour and the cloud
 *
 * The cloud takes its hue from the active persona's `themeColor` ([liveHudPalette] lifts a dark
 * or neutral theme into a visible range without inventing a hue). The state changes brightness
 * and turbulence, not hue. The cloud itself is one SkSL fragment shader ([NebulaRenderer]); when
 * Skia rejects it, [LiveCore] draws a layered-gradient fallback, so a failed shader degrades the
 * look rather than the feature.
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
        personaColor = persona?.themeColor ?: Color.Cyan,
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
    val palette = remember(personaColor) { liveHudPalette(personaColor) }
    val nebula = remember { NebulaRenderer() }
    DisposableEffect(nebula) {
        onDispose { nebula.close() }
    }

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
        targetValue = liveHudAccent(state, personaColor),
        animationSpec = tween(durationMillis = 650),
        label = "live-hud-accent",
    )
    val energy by animateFloatAsState(
        targetValue = liveHudEnergy(state),
        animationSpec = tween(durationMillis = 900),
        label = "live-hud-energy",
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
            palette = palette,
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
                palette = palette,
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
                    palette = palette,
                    time = time,
                    level = level,
                    history = history,
                    energy = energy,
                    nebula = nebula,
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
                palette = palette,
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
    palette: LiveHudPalette,
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
                color = palette.muted.copy(alpha = 0.72f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = now.format(HUD_TIME),
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 10.sp else 12.sp,
                    letterSpacing = 2.sp,
                    color = palette.muted.copy(alpha = 0.88f),
                )
                Text(
                    text = if (usage.totalTokens > 0) "TOK ${usage.totalTokens}" else "STANDBY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    letterSpacing = 1.5.sp,
                    color = palette.muted.copy(alpha = 0.55f),
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
                tint = palette.muted.copy(alpha = 0.78f),
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
    palette: LiveHudPalette,
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
                color = palette.muted.copy(alpha = 0.55f),
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
                        palette.hot.copy(alpha = 0.56f)
                    } else {
                        palette.hot.copy(alpha = 0.95f)
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
                    color = palette.muted.copy(alpha = 0.42f),
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
    palette: LiveHudPalette,
    accent: Color,
    modifier: Modifier,
) {
    val stars = remember {
        val rng = kotlin.random.Random(20)
        List(120) { Triple(rng.nextFloat(), rng.nextFloat(), rng.nextFloat()) }
    }
    Canvas(modifier = modifier) {
        // Static on purpose. The backdrop is scenery; the cloud is the thing that moves.
        drawRect(Brush.verticalGradient(listOf(palette.background, palette.backgroundDeep)))
        drawRect(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.18f), Color.Transparent),
                center = center,
                radius = size.minDimension * 0.80f,
            )
        )
        // A few large, almost invisible tinted clouds: the backdrop is hazy rather than a clean
        // black field. Static, so they cost one draw per accent change.
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
                    colors = listOf(palette.primary.copy(alpha = 0.05f), Color.Transparent),
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
                    palette.hot.copy(alpha = twinkle)
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
    palette: LiveHudPalette,
    time: State<Float>,
    level: State<Float>,
    history: LevelHistory,
    energy: Float,
    nebula: NebulaRenderer,
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

            // The cloud is the subject now: no rings, spokes, lattice or equator. Those were the
            // "spinning wheel" the user rejected; this is one volume of light with a hot centre.
            val drawn = if (nebula.available) {
                var painted = false
                drawIntoCanvas { canvas ->
                    painted = nebula.draw(
                        canvas = canvas.skiaCanvas,
                        width = size.width,
                        height = size.height,
                        timeSeconds = t,
                        level = lvl,
                        energy = energy,
                        primary = accent.rgb(),
                        secondary = palette.secondary.rgb(),
                        hot = palette.hot.rgb(),
                        deep = palette.deep.rgb(),
                    )
                }
                painted
            } else {
                false
            }
            if (!drawn) drawNebulaFallback(accent, palette, t, lvl)

            // A horizontal voice trace under the cloud. It is the shape a voice visualiser is
            // recognised by, and it keeps the audio interaction legible without turning the
            // cloud into a ring.
            if (state != AssistantState.THINKING) {
                val samples = 64
                val waveY = size.height * 0.90f
                val halfWidth = size.minDimension * 0.34f
                val path = Path()
                for (i in 0 until samples) {
                    val v = history.at(i * 2)
                    val x = c.x - halfWidth + (i / (samples - 1f)) * halfWidth * 2f
                    val y = waveY - v * size.minDimension * 0.075f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = accent.copy(alpha = 0.10f),
                    style = Stroke(width = maxOf(2f, size.minDimension * 0.010f)),
                )
                drawPath(
                    path = path,
                    color = accent.copy(alpha = 0.62f),
                    style = Stroke(width = maxOf(1f, size.minDimension * 0.0025f)),
                )
            }

            // Four corner brackets: the HUD's frame, not an orbit.
            val inset = size.minDimension * 0.035f
            val bracket = size.minDimension * 0.10f
            val cornerColor = accent.copy(alpha = 0.22f)
            val lineWidth = maxOf(1f, size.minDimension * 0.0025f)
            fun bracketAt(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(cornerColor, Offset(x, y), Offset(x + dx, y), lineWidth)
                drawLine(cornerColor, Offset(x, y), Offset(x, y + dy), lineWidth)
            }
            bracketAt(inset, inset, bracket, bracket)
            bracketAt(size.width - inset, inset, -bracket, bracket)
            bracketAt(inset, size.height - inset, bracket, -bracket)
            bracketAt(size.width - inset, size.height - inset, -bracket, -bracket)
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
                color = accent.copy(alpha = 0.82f),
            )
            if (state == AssistantState.IDLE) {
                Text(
                    text = "TAP CORE TO TALK",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                    letterSpacing = 2.sp,
                    color = palette.muted.copy(alpha = 0.50f),
                )
            }
        }
    }
}

/**
 * The shader-free cloud, used only when Skia rejects the SkSL. It is deliberately simpler than
 * the shader — a tinted core with three offset lobes — but it still reads as a living volume, so
 * a failed compile degrades the look rather than the feature.
 */
private fun DrawScope.drawNebulaFallback(
    accent: Color,
    palette: LiveHudPalette,
    timeSeconds: Float,
    level: Float,
) {
    val breath = 1f + 0.04f * sin(timeSeconds * 0.6f) + 0.08f * level
    val radius = size.minDimension * 0.36f * breath
    val centre = center
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.42f),
                palette.secondary.copy(alpha = 0.20f),
                Color.Transparent,
            ),
            center = centre,
            radius = radius * 1.8f,
        ),
        radius = radius * 1.8f,
        center = centre,
    )
    for (i in 0..2) {
        val angle = timeSeconds * 0.11f + i * 2.094f
        val offset = Offset(
            centre.x + cos(angle) * radius * 0.28f,
            centre.y + sin(angle * 1.3f) * radius * 0.20f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.primary.copy(alpha = 0.32f),
                    palette.secondary.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                center = offset,
                radius = radius * 0.85f,
            ),
            radius = radius * 0.85f,
            center = offset,
        )
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.hot.copy(alpha = 0.90f),
                accent.copy(alpha = 0.30f),
                Color.Transparent,
            ),
            center = centre,
            radius = radius * 0.52f,
        ),
        radius = radius * 0.52f,
        center = centre,
    )
}

/** The shader wants three floats; Compose colours are float components already. */
private fun Color.rgb(): FloatArray = floatArrayOf(red, green, blue)

private val HUD_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
