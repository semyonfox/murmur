package dev.local.murmur.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.local.murmur.AnonymousTelemetry
import dev.local.murmur.LectureService
import dev.local.murmur.LectureState
import dev.local.murmur.R
import dev.local.murmur.TelemetryRoute

internal sealed interface Route {
    val key: String

    enum class Tab(val label: String) : Route {
        HOME("Home"), LECTURES("Lectures"), HISTORY("History"), SETTINGS("Settings");
        override val key: String get() = name
    }

    enum class Page : Route {
        RECORDER, STATS, RECOGNITION, CLEANUP, WORDS, BUBBLE, STORAGE, ABOUT;
        override val key: String get() = name
    }

    data class Lecture(val id: Long) : Route {
        override val key: String get() = "LECTURE:$id"
    }

    companion object {
        fun parse(key: String): Route? = when {
            key.startsWith("LECTURE:") -> key.removePrefix("LECTURE:").toLongOrNull()?.let(::Lecture)
            else -> Tab.entries.firstOrNull { it.name == key } ?: Page.entries.firstOrNull { it.name == key }
        }
    }
}

// a plain back stack is enough for a dozen screens and keeps navigation free of extra libraries
internal class Navigator(val stack: SnapshotStateList<Route>) {
    val current: Route get() = stack.last()

    fun push(route: Route) {
        if (current != route) stack.add(route)
    }

    fun replace(route: Route) {
        stack[stack.lastIndex] = route
    }

    fun pop(): Boolean {
        if (stack.size <= 1) {
            if (current == Route.Tab.HOME) return false
            stack[0] = Route.Tab.HOME
            return true
        }
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun select(tab: Route.Tab) {
        stack.clear()
        stack.add(tab)
    }

    fun showLecture(id: Long) {
        stack.removeAll { it == Route.Page.RECORDER || (it is Route.Lecture && it.id == id) }
        if (stack.isEmpty()) stack.add(Route.Tab.LECTURES)
        if (stack.first() != Route.Tab.LECTURES) {
            stack.clear()
            stack.add(Route.Tab.LECTURES)
        }
        stack.add(Route.Lecture(id))
    }
}

@Composable
private fun rememberNavigator(initial: Route.Tab): Navigator {
    val stack = rememberSaveable(saver = listSaver(
        save = { list -> list.map { it.key } },
        restore = { keys -> mutableStateListOf<Route>().apply { addAll(keys.mapNotNull(Route::parse)) } },
    )) { mutableStateListOf<Route>(initial) }
    if (stack.isEmpty()) stack.add(initial)
    return remember(stack) { Navigator(stack) }
}

@Composable
internal fun MurmurApp(model: AppModel, openLecture: Long?, onOpenLectureHandled: () -> Unit) {
    val navigator = rememberNavigator(Route.Tab.HOME)
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messenger = remember(snackbarHost, scope) { Messenger(snackbarHost, scope) }
    val haptics = LocalHapticFeedback.current
    val lecture by LectureService.state.collectAsState()

    LaunchedEffect(openLecture) {
        val id = openLecture ?: return@LaunchedEffect
        if (lecture.phase == LectureState.Phase.RECORDING) {
            navigator.select(Route.Tab.LECTURES)
            navigator.push(Route.Page.RECORDER)
        } else if (id > 0) navigator.showLecture(id)
        else navigator.select(Route.Tab.LECTURES)
        onOpenLectureHandled()
    }
    LaunchedEffect(lecture.message, lecture.busy) {
        val message = lecture.message ?: return@LaunchedEffect
        if (lecture.busy) return@LaunchedEffect
        if (lecture.failed) haptics.performHapticFeedback(HapticFeedbackType.Reject)
        messenger.showLater(message)
        LectureService.consumeMessage()
        model.refresh()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(navigator.current) {
        AnonymousTelemetry.screen(context, when (navigator.current) {
            Route.Tab.HOME -> TelemetryRoute.HOME
            Route.Tab.HISTORY, Route.Tab.LECTURES, is Route.Lecture, Route.Page.RECORDER -> TelemetryRoute.LIBRARY
            else -> TelemetryRoute.SETTINGS
        })
    }
    BackHandler(enabled = navigator.stack.size > 1 || navigator.current != Route.Tab.HOME) { navigator.pop() }

    CompositionLocalProvider(LocalSnackbar provides messenger) {
        val lectureActions = rememberLectureActions(
            model,
            onRecording = {
                navigator.select(Route.Tab.LECTURES)
                navigator.push(Route.Page.RECORDER)
            },
            onOpenRecognition = { navigator.push(Route.Page.RECOGNITION) },
        )
        val current = navigator.current
        val backdrop = rememberGraphicsLayer()
        val dock = rememberDockState()
        var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
        val onTab = current is Route.Tab
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(dock.connection)
                    .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                    .drawWithContent {
                        backdrop.record { this@drawWithContent.drawContent() }
                        drawLayer(backdrop)
                    }
                    // pushed pages have no dock, so they stop at the navigation bar
                    .then(if (onTab) Modifier else Modifier.navigationBarsPadding()),
            ) {
                AnimatedContent(
                    targetState = navigator.stack.toList(),
                    contentKey = { it.last().key },
                    transitionSpec = { transition(initialState, targetState) },
                    label = "screen",
                ) { stack ->
                    CompositionLocalProvider(LocalDock provides dock) {
                        Screen(stack.last(), model, navigator, lectureActions)
                    }
                }
            }
            AnimatedVisibility(
                visible = onTab,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                GlassDock(
                    backdrop = backdrop,
                    backdropOrigin = backdropOrigin,
                    selected = (current as? Route.Tab) ?: Route.Tab.HOME,
                    expanded = dock.expanded,
                    onSelect = { tab ->
                        if (current != tab) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        navigator.select(tab)
                    },
                )
            }
            SnackbarHost(
                snackbarHost,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (onTab) DockClearance else 0.dp),
            )
        }
    }
}

private fun transition(from: List<Route>, to: List<Route>): ContentTransform {
    val forward = to.size > from.size
    val target = to.last()
    val origin = from.last()
    return when {
        target == Route.Page.RECORDER -> (slideInVertically(tween(320)) { it / 3 } + fadeIn(tween(220)) + scaleIn(initialScale = 0.96f))
            .togetherWith(fadeOut(tween(160)))
        origin == Route.Page.RECORDER -> fadeIn(tween(220)).togetherWith(slideOutVertically(tween(260)) { it / 3 } + fadeOut(tween(200)))
        target is Route.Tab && origin is Route.Tab -> fadeIn(tween(220, delayMillis = 60)).togetherWith(fadeOut(tween(90)))
        forward -> (slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(220)))
            .togetherWith(slideOutHorizontally(tween(300)) { -it / 8 } + fadeOut(tween(150)))
        else -> (slideInHorizontally(tween(300)) { -it / 8 } + fadeIn(tween(220)))
            .togetherWith(slideOutHorizontally(tween(300)) { it / 4 } + fadeOut(tween(150)))
    }
}

@Composable
internal fun TabIcon(tab: Route.Tab) {
    when (tab) {
        Route.Tab.HOME -> Icon(Icons.Filled.Home, contentDescription = null)
        Route.Tab.LECTURES -> Icon(painterResource(R.drawable.ic_school), contentDescription = null)
        Route.Tab.HISTORY -> Icon(painterResource(R.drawable.ic_history), contentDescription = null)
        Route.Tab.SETTINGS -> Icon(Icons.Filled.Settings, contentDescription = null)
    }
}

@Composable
private fun Screen(route: Route, model: AppModel, navigator: Navigator, lectureActions: LectureActions) {
    val back = { navigator.pop(); Unit }
    when (route) {
        Route.Tab.HOME -> HomeScreen(model, lectureActions, navigate = navigator::push,
            selectTab = navigator::select)
        Route.Tab.LECTURES -> LecturesScreen(model, lectureActions,
            onOpenLecture = { navigator.push(Route.Lecture(it)) },
            onOpenRecorder = { navigator.push(Route.Page.RECORDER) })
        Route.Tab.HISTORY -> HistoryScreen(model, onOpenStorage = { navigator.push(Route.Page.STORAGE) })
        Route.Tab.SETTINGS -> SettingsScreen(model, navigate = navigator::push)
        Route.Page.RECORDER -> RecorderScreen(onClose = back, onFinished = navigator::showLecture)
        Route.Page.STATS -> StatsScreen(model, onBack = back)
        Route.Page.RECOGNITION -> RecognitionScreen(model, onBack = back)
        Route.Page.CLEANUP -> CleanupScreen(model, onBack = back)
        Route.Page.WORDS -> WordsScreen(model, onBack = back)
        Route.Page.BUBBLE -> BubbleScreen(model, onBack = back)
        Route.Page.STORAGE -> StorageScreen(model, onBack = back)
        Route.Page.ABOUT -> AboutScreen(model, onBack = back)
        is Route.Lecture -> LectureDetailScreen(model, route.id, onBack = back,
            onOpenRecognition = { navigator.push(Route.Page.RECOGNITION) })
    }
}
