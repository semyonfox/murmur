package dev.local.murmur.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp

private val DockHeight = 64.dp
private val CompactHeight = 56.dp
private val DockMargin = 12.dp

// how much room a tab leaves above the navigation bar so nothing hides under the dock
internal val DockClearance = DockHeight + DockMargin

// shrinks the dock to icons after 20 dp of scrolling down and brings it back after
// 20 dp up or near the top of a list, matching the old view based dock
@Stable
internal class DockState(private val thresholdPx: Float) {
    var expanded by mutableStateOf(true)
        private set
    private var travel = 0f
    private var nearTop = true

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy == 0f || nearTop) return Offset.Zero
            if (dy * travel < 0f) travel = 0f
            travel += dy
            if (travel <= -thresholdPx) {
                expanded = false
                travel = 0f
            } else if (travel >= thresholdPx) {
                expanded = true
                travel = 0f
            }
            return Offset.Zero
        }
    }

    fun setNearTop(value: Boolean) {
        nearTop = value
        if (value) {
            expanded = true
            travel = 0f
        }
    }
}

internal val LocalDock = staticCompositionLocalOf<DockState?> { null }

@Composable
internal fun rememberDockState(): DockState {
    val threshold = with(LocalDensity.current) { 20.dp.toPx() }
    return remember(threshold) { DockState(threshold) }
}

// a floating frosted pill. the screen content is recorded into [backdrop] and the
// dock draws a blurred copy of the part behind it, the same trick the old view
// based dock used with a bitmap, but on the render thread with no extra copies
@Composable
internal fun GlassDock(
    backdrop: GraphicsLayer,
    backdropOrigin: Offset,
    selected: Route.Tab,
    expanded: Boolean,
    onSelect: (Route.Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(if (expanded) 1f else 0f, tween(280), label = "dock")
    val screenWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
    val expandedWidth = minOf(screenWidth - 28, 420).dp
    val compactWidth = maxOf(248, minOf(screenWidth - 80, 350)).dp
    val shape = RoundedCornerShape(32.dp)
    val colors = MaterialTheme.colorScheme
    val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .navigationBarsPadding()
            .padding(bottom = DockMargin)
            .width(lerp(compactWidth, expandedWidth, progress))
            .height(lerp(CompactHeight, DockHeight, progress))
            .onGloballyPositioned { origin = it.positionInRoot() - backdropOrigin }
            .shadow(12.dp, shape)
            .clip(shape),
    ) {
        if (blurs) Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { renderEffect = BlurEffect(24f, 24f, TileMode.Clamp) }
                .drawBehind {
                    drawRect(colors.surface)
                    translate(-origin.x, -origin.y) { drawLayer(backdrop) }
                },
        )
        Box(
            Modifier
                .matchParentSize()
                .background(colors.surfaceContainerHigh.copy(alpha = if (blurs) 0.6f else 0.96f))
                .border(1.dp, colors.onSurface.copy(alpha = 0.1f), shape),
        )
        Row(
            Modifier.fillMaxSize().padding(4.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Route.Tab.entries.forEach { tab ->
                val active = tab == selected
                val tint by animateColorAsState(if (active) colors.primary else colors.onSurfaceVariant, label = "tab tint")
                val fill by animateColorAsState(
                    if (active) colors.primaryContainer.copy(alpha = 0.45f) else colors.primaryContainer.copy(alpha = 0f),
                    label = "tab fill",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(28.dp))
                        .background(fill)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides tint) { TabIcon(tab) }
                    if (progress > 0f) Text(
                        tab.label,
                        color = tint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.heightIn(max = 18.dp * progress).graphicsLayer { alpha = progress },
                    )
                }
            }
        }
    }
}
