package dev.local.murmur

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.local.murmur.ui.AppModel
import dev.local.murmur.ui.MurmurApp
import dev.local.murmur.ui.MurmurTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private var openLecture by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AnonymousTelemetry.open(this)
        model = AppModel(this)
        if (savedInstanceState == null) openLecture = lectureFrom(intent)
        setContent {
            MurmurTheme {
                MurmurApp(model, openLecture, onOpenLectureHandled = { openLecture = null })
            }
        }
        if (savedInstanceState == null) model.checkForUpdates(automatic = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lectureFrom(intent)?.let { openLecture = it }
    }

    override fun onResume() {
        super.onResume()
        // MIUI and friends kill the ready service with the app, bring it back if the user left it on
        if (MurmurReadyService.isWanted(this) && !MurmurReadyService.isActive && !LectureService.isActive &&
            model.bubbleEnabled && model.micAllowed
        ) runCatching { MurmurReadyService.start(this) }
            .onFailure { Diagnostics.warn("app", "could not restart the ready service", it) }
        model.refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // system sheets like the accessibility settings return without a resume
        if (hasFocus) model.refresh()
    }

    private fun lectureFrom(intent: Intent?): Long? =
        intent?.takeIf { it.hasExtra(EXTRA_OPEN_LECTURE) }?.getLongExtra(EXTRA_OPEN_LECTURE, 0L)

    companion object {
        const val EXTRA_OPEN_LECTURE = "open_lecture"
    }
}
