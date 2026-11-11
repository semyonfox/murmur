package dev.local.murmur.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.local.murmur.LectureService
import dev.local.murmur.LectureState
import dev.local.murmur.LectureTranscript
import dev.local.murmur.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecorderScreen(onClose: () -> Unit, onFinished: (Long) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by LectureService.state.collectAsState()
    val levels by LectureService.levels.collectAsState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    // the service starts asynchronously, so only leave once it has actually been busy
    var seenBusy by remember { mutableStateOf(false) }

    LaunchedEffect(state.phase, state.lectureId) {
        if (state.busy) seenBusy = true
        when {
            state.phase == LectureState.Phase.TRANSCRIBING && state.lectureId != 0L -> onFinished(state.lectureId)
            seenBusy && !state.busy -> onClose()
        }
    }

    val recording = state.phase == LectureState.Phase.RECORDING
    val paused = recording && state.paused
    val red = LocalExtraColors.current.recording

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Hide recorder, keep recording")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.6f))
            StatusPill(recording = recording, paused = paused, red = red)
            Spacer(Modifier.height(20.dp))
            Text(
                LectureTranscript.clock(state.recordedMs),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.Light,
                ),
                color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = "Recorded ${LectureTranscript.clock(state.recordedMs)}" },
            )
            Spacer(Modifier.height(32.dp))
            Waveform(
                levels = levels,
                color = if (paused) MaterialTheme.colorScheme.outline else red,
                baseline = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.fillMaxWidth().height(140.dp).alpha(if (paused) 0.5f else 1f),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                if (paused) "Paused. Nothing is being recorded until you resume."
                else "You can lock your phone or switch apps. Recording carries on and the notification has controls.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); confirmDiscard = true },
                    enabled = recording,
                    modifier = Modifier.size(64.dp),
                ) { Icon(Icons.Filled.Delete, contentDescription = "Discard recording") }

                PauseButton(
                    paused = paused,
                    enabled = recording,
                    color = red,
                    onColor = LocalExtraColors.current.onRecording,
                    onClick = {
                        if (paused) {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            LectureService.resume(context)
                        } else {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            LectureService.pause(context)
                        }
                    },
                )

                FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        LectureService.finish(context)
                    },
                    enabled = recording,
                    modifier = Modifier.size(64.dp),
                ) { Icon(Icons.Filled.Check, contentDescription = "Finish and transcribe") }
            }
        }
    }

    if (confirmDiscard) ConfirmDialog(
        title = "Discard this recording?",
        text = "The audio is deleted from this phone and nothing is transcribed.",
        confirm = "Discard",
        onConfirm = {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            LectureService.discard(context)
        },
        onDismiss = { confirmDiscard = false },
    )
}

@Composable
private fun StatusPill(recording: Boolean, paused: Boolean, red: androidx.compose.ui.graphics.Color) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    )
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp)
                    .alpha(if (recording && !paused) dotAlpha else 1f)
                    .background(if (recording && !paused) red else MaterialTheme.colorScheme.outline, CircleShape),
            )
            Spacer(Modifier.size(8.dp))
            AnimatedContent(
                targetState = when {
                    paused -> "Paused"
                    recording -> "Recording"
                    else -> "Starting…"
                },
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "status",
            ) { label -> Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

// the big control morphs between a circle and a rounded square like the system recorder
@Composable
private fun PauseButton(
    paused: Boolean,
    enabled: Boolean,
    color: androidx.compose.ui.graphics.Color,
    onColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val corner by animateDpAsState(if (paused) 48.dp else 28.dp, spring(dampingRatio = 0.6f), label = "corner")
    val scale by animateFloatAsState(if (paused) 1f else 0.94f, spring(dampingRatio = 0.5f), label = "scale")
    IconButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = onColor),
        modifier = Modifier.size((96 * scale).dp),
    ) {
        Icon(
            painterResource(if (paused) R.drawable.ic_microphone else R.drawable.ic_pause),
            contentDescription = if (paused) "Resume recording" else "Pause recording",
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
internal fun Waveform(
    levels: List<Float>,
    color: androidx.compose.ui.graphics.Color,
    baseline: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val barWidth = 3.dp.toPx()
        val gap = 2.5.dp.toPx()
        val step = barWidth + gap
        val slots = (size.width / step).toInt().coerceAtLeast(1)
        val middle = size.height / 2
        drawLine(baseline, Offset(0f, middle), Offset(size.width, middle), strokeWidth = 1.dp.toPx())
        val visible = levels.takeLast(slots)
        val start = size.width - visible.size * step
        visible.forEachIndexed { index, level ->
            // a small floor keeps silence visible as a dotted line rather than nothing
            val height = (level * level * size.height * 0.95f).coerceAtLeast(barWidth)
            drawRoundRect(
                color = color,
                topLeft = Offset(start + index * step, middle - height / 2),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}
