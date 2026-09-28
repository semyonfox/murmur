package dev.local.murmur

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.provider.OpenableColumns
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import android.util.JsonWriter
import java.io.OutputStreamWriter
import java.io.File
import java.net.URI
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private val history by lazy { HistoryStore(this) }
    private lateinit var scroll: ScrollView
    private lateinit var pageContent: LinearLayout
    private lateinit var navigation: LinearLayout
    private var dockBlur: ImageView? = null
    private var dockBlurBitmap: Bitmap? = null
    private var dockBlurQueued = false
    private var dockBlurLastCapture = 0L
    private lateinit var pageTitle: TextView
    private lateinit var pageDescription: TextView
    private val pages = mutableMapOf<Page, LinearLayout>()
    private val navigationItems = mutableMapOf<Page, LinearLayout>()
    private val navigationIcons = mutableMapOf<Page, ImageView>()
    private val navigationLabels = mutableMapOf<Page, TextView>()
    private var dockExpanded = true
    private var dockProgress = 1f
    private var dockPreviousOffset = 0
    private var dockDownwardTravel = 0
    private var dockUpwardTravel = 0
    private var dockAnimator: ValueAnimator? = null
    private var currentPage = Page.HOME
    private var speechBackCallback: OnBackInvokedCallback? = null
    private lateinit var speechCard: LinearLayout
    private lateinit var localModelsList: LinearLayout
    private lateinit var modelDownloadStatus: TextView
    private lateinit var onDeviceSwitch: Switch
    private lateinit var onlineSettings: LinearLayout
    private lateinit var onlineChoiceButton: Button
    private lateinit var speechLanguageInput: EditText
    private lateinit var translateSwitch: Switch
    private val modelProgressRefresh = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            refreshLocalModels()
            if (LocalSpeechModels.downloadingId != null) window.decorView.postDelayed(this, 1_000)
        }
    }
    private lateinit var endpointInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var keyStatusView: TextView
    private lateinit var removeKeyButton: Button
    private lateinit var feedbackView: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var heroButton: Button
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private var availableUpdate: AvailableUpdate? = null
    private var downloadedUpdate: File? = null
    private var updateBusy = false
    private lateinit var inputBubbleRow: SetupRow
    private lateinit var keyboardRow: SetupRow
    private lateinit var selectedRow: SetupRow
    private lateinit var latestView: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var lectureList: LinearLayout
    private lateinit var lectureStatus: TextView
    private lateinit var lectureRecordButton: Button
    private lateinit var homeLectureRecordButton: Button
    private lateinit var homeLectureStatus: TextView
    private lateinit var homeStatsSummary: TextView
    private lateinit var retentionButton: Button
    private lateinit var historyFeedback: TextView
    private lateinit var statsPaceView: TextView
    private lateinit var statsWordsView: TextView
    private lateinit var statsTotalView: TextView
    private lateinit var statsMinutesView: TextView
    private lateinit var statsTrendView: TextView
    private lateinit var statsMethodView: TextView
    private lateinit var costRows: LinearLayout
    private lateinit var costStatus: TextView
    private lateinit var costWeekView: TextView
    private lateinit var costMonthView: TextView
    private lateinit var costAllView: TextView
    private lateinit var costByokNote: TextView
    private lateinit var costRefreshButton: Button
    private var costLoading = false
    private lateinit var weeklyWordsChart: LinearLayout
    private lateinit var weeklyPaceRow: LinearLayout
    private lateinit var weeklyPaceTitle: TextView
    private lateinit var rawTranscriptCard: LinearLayout
    private lateinit var rawTranscriptView: TextView
    private lateinit var homeLatestView: TextView
    private lateinit var homeLatestDateView: TextView
    private lateinit var homeLatestLinkView: TextView
    private lateinit var homeRecentCard: LinearLayout
    private val presetButtons = mutableListOf<Pair<Button, SpeechPreset>>()
    private lateinit var cleanupUrlInput: EditText
    private lateinit var cleanupModelInput: EditText
    private lateinit var cleanupKeyInput: EditText
    private lateinit var cleanupEnabledSwitch: Switch
    private lateinit var cleanupFormality: Spinner
    private lateinit var cleanupFeedback: TextView
    private lateinit var cleanupStatus: TextView
    private lateinit var cleanupKeyStatus: TextView
    private lateinit var cleanupRemoveKeyButton: Button
    private lateinit var cleanupPromptSummary: TextView
    private lateinit var speechRecognitionSummary: TextView
    private lateinit var speechCleanupSummary: TextView
    private var updatingCleanupSwitch = false
    private lateinit var dictionaryInput: EditText
    private lateinit var dictionaryList: LinearLayout
    private var heroAction: () -> Unit = {}
    private var pendingReadyStart = false
    private var pendingLectureStart = false
    private val lectureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLectures()
        }
    }

    private data class SpeechPreset(val label: String, val url: String, val model: String)
    private enum class Page(val label: String, val description: String, val icon: Int) {
        HOME("Home", "Your dictation at a glance.", R.drawable.ic_home),
        SPEECH("Models", "Choose speech recognition and transcript cleanup.", R.drawable.ic_microphone),
        HISTORY("History", "Past dictations kept on this phone.", R.drawable.ic_document),
        WORDS("Words", "Names and terms Murmur should spell your way.", R.drawable.ic_dictionary),
        INPUT("Input", "Set up the bubble or use the Murmur keyboard.", R.drawable.ic_keyboard),
        RECOGNITION("Recognition", "Choose where recordings are transcribed.", R.drawable.ic_microphone),
        CLEANUP("Cleanup", "Tidy transcripts before they are inserted.", R.drawable.ic_microphone),
        LECTURES("Lectures", "Record a lecture or transcribe an audio file.", R.drawable.ic_microphone),
        STATS("Stats", "Your dictation activity and provider usage.", R.drawable.ic_home),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        currentPage = savedInstanceState?.getString("page")?.let { saved ->
            Page.entries.firstOrNull { it.name == saved }
        } ?: Page.HOME

        pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(112))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(pageContent)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                onPageScroll(scrollY)
                scheduleDockBlur()
            }
        }
        navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(R.color.murmur_surface, 32f, stroke = R.color.murmur_border).apply {
                alpha = 230
            }
            elevation = dp(8).toFloat()
        }
        val shell = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(color(R.color.murmur_background))
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val dockWidth = dp(minOf(resources.configuration.screenWidthDp - 28, 420))
            if (Build.VERSION.SDK_INT >= 31) {
                dockBlur = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dp(32).toFloat()
                    }
                    clipToOutline = true
                    setRenderEffect(RenderEffect.createBlurEffect(dp(10).toFloat(), dp(10).toFloat(), Shader.TileMode.CLAMP))
                }
                addView(dockBlur, FrameLayout.LayoutParams(dockWidth, dp(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(12)
                })
            }
            addView(navigation, FrameLayout.LayoutParams(dockWidth, dp(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(12)
            })
        }
        setContentView(shell)
        navigation.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleDockBlur() }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleDockBlur() }

        pageTitle = text("", 28f, R.color.murmur_text, bold = true)
        pageDescription = text("", 13f, R.color.murmur_muted).apply { setPadding(0, dp(2), 0, dp(16)) }
        pageContent.addView(pageTitle)
        pageContent.addView(pageDescription)
        val mainTabs = listOf(Page.HOME, Page.SPEECH, Page.HISTORY, Page.WORDS, Page.INPUT)
        Page.entries.forEach { page ->
            pages[page] = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pageContent.addView(pages.getValue(page))
            if (page !in mainTabs) return@forEach
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(56)
                contentDescription = "${page.label} section"
                setOnClickListener { showPage(page) }
            }
            val icon = ImageView(this).apply {
                setImageResource(page.icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = text(page.label, 12f, R.color.murmur_muted, bold = true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0, dp(2), 0, 0)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            item.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            navigationItems[page] = item
            navigationIcons[page] = icon
            navigationLabels[page] = label
            navigation.addView(item, LinearLayout.LayoutParams(0, dp(56), 1f))
        }

        val home = pages.getValue(Page.HOME)
        val speech = pages.getValue(Page.SPEECH)
        val recognition = pages.getValue(Page.RECOGNITION)
        val cleanup = pages.getValue(Page.CLEANUP)
        val transcript = pages.getValue(Page.HISTORY)
        val lectures = pages.getValue(Page.LECTURES)
        val statsPage = pages.getValue(Page.STATS)
        val words = pages.getValue(Page.WORDS)
        val inputPage = pages.getValue(Page.INPUT)

        val quickActions = card().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        quickActions.addView(text("Lectures", 17f, R.color.murmur_text, bold = true))
        quickActions.addView(text("Record now or transcribe an audio file. Find recordings in Lectures.",
            12f, R.color.murmur_muted).apply { setPadding(0, dp(4), 0, dp(10)) })
        homeLectureStatus = text("", 12f, R.color.murmur_muted)
        quickActions.addView(homeLectureStatus)
        val quickButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        homeLectureRecordButton = button("Record lecture", primary = true) { onLectureRecord() }
        quickButtons.addView(homeLectureRecordButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quickButtons.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        quickButtons.addView(button("Import audio", primary = false) { chooseLectureAudio() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quickActions.addView(quickButtons)
        quickActions.addView(text("View saved lectures  ↗", 13f, R.color.murmur_accent, bold = true).apply {
            setPadding(0, dp(12), 0, dp(12))
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showPage(Page.LECTURES) }
        })
        home.addView(quickActions)

        val statsCard = card()
        val statsIntro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(14))
            addView(text("Your dictation stats", 16f, R.color.murmur_text, bold = true))
            addView(text("Based on retained history. Deleted dictations aren't counted.", 12f, R.color.murmur_muted).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }
        statsCard.addView(statsIntro)
        divider(statsCard)
        val (totalCell, totalValue) = statsCell("Retained words")
        statsTotalView = totalValue
        val (weekCell, weekValue) = statsCell("This week")
        statsWordsView = weekValue
        statsCard.addView(statsRow(totalCell, weekCell))
        divider(statsCard)
        val (paceCell, paceValue) = statsCell("Recorded pace")
        statsPaceView = paceValue
        val (minutesCell, minutesValue) = statsCell("Recorded minutes")
        statsMinutesView = minutesValue
        statsCard.addView(statsRow(paceCell, minutesCell))
        divider(statsCard)
        val chartSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(text("Words each week", 14f, R.color.murmur_text, bold = true))
        }
        statsTrendView = text("", 12f, R.color.murmur_muted).apply { setPadding(0, dp(3), 0, dp(14)) }
        chartSection.addView(statsTrendView)
        weeklyWordsChart = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        chartSection.addView(weeklyWordsChart)
        weeklyPaceTitle = text("Recorded pace each week", 13f, R.color.murmur_text, bold = true).apply {
            setPadding(0, dp(18), 0, dp(8))
        }
        chartSection.addView(weeklyPaceTitle)
        weeklyPaceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chartSection.addView(weeklyPaceRow)
        statsMethodView = text("", 11f, R.color.murmur_muted).apply {
            setPadding(0, dp(17), 0, 0)
        }
        chartSection.addView(statsMethodView)
        statsCard.addView(chartSection)
        statsPage.addView(statsCard)

        statsPage.addView(groupTitle("Provider cost"))
        val costCard = card().apply { setPadding(dp(16), dp(15), dp(16), dp(16)) }
        val costHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val costHeading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        costHeading.addView(text("OpenRouter usage", 16f, R.color.murmur_text, bold = true))
        costHeading.addView(text("For your configured OpenRouter keys", 12f, R.color.murmur_muted))
        costHeader.addView(costHeading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        costRefreshButton = button("Refresh", primary = false) { refreshProviderCost() }
        costHeader.addView(costRefreshButton)
        costCard.addView(costHeader)
        costStatus = text("", 12f, R.color.murmur_muted).apply { setPadding(0, dp(12), 0, 0) }
        costCard.addView(costStatus)
        costRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        costWeekView = costRow(costRows, "This week")
        costMonthView = costRow(costRows, "This month")
        costAllView = costRow(costRows, "All time")
        costCard.addView(costRows)
        costByokNote = text("", 12f, R.color.murmur_muted).apply { setPadding(0, dp(10), 0, 0) }
        costCard.addView(costByokNote)
        costCard.addView(text("These are OpenRouter key totals, including use outside Murmur. Local models and direct provider keys are not counted.",
            12f, R.color.murmur_muted).apply { setPadding(0, dp(12), 0, 0) })
        statsPage.addView(costCard)

        home.addView(groupTitle("Recent dictation"))
        val recent = card().apply { setPadding(dp(18), dp(16), dp(18), dp(16)) }
        homeRecentCard = recent
        homeLatestDateView = text("", 12f, R.color.murmur_muted).apply { setPadding(0, 0, 0, dp(7)) }
        recent.addView(homeLatestDateView)
        homeLatestView = text("", 15f, R.color.murmur_text).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        recent.addView(homeLatestView)
        homeLatestLinkView = text("View transcript  ↗", 13f, R.color.murmur_accent, bold = true).apply {
            setPadding(0, dp(14), 0, 0)
        }
        recent.addView(homeLatestLinkView)
        recent.contentDescription = "View latest transcript"
        recent.setOnClickListener { showPage(Page.HISTORY) }
        home.addView(recent)

        val status = card().apply { setPadding(dp(16), dp(15), dp(16), dp(16)) }
        status.addView(text("Dictation", 17f, R.color.murmur_text, bold = true).apply {
            setPadding(0, 0, 0, dp(8))
        })
        statusTitle = text("", 15f, R.color.murmur_text, bold = true)
        statusDetail = text("", 12f, R.color.murmur_muted).apply { setPadding(0, dp(3), 0, dp(10)) }
        heroButton = button("", primary = true) { heroAction() }
        status.addView(statusTitle)
        status.addView(statusDetail)
        status.addView(heroButton)
        home.addView(status, 1)

        home.addView(groupTitle("Activity"))
        val statsPreview = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        homeStatsSummary = text("", 15f, R.color.murmur_text, bold = true)
        statsPreview.addView(homeStatsSummary)
        statsPreview.addView(text("View stats and provider cost  ↗", 13f, R.color.murmur_accent).apply {
            setPadding(0, dp(8), 0, 0)
        })
        statsPreview.contentDescription = "View dictation stats and provider cost"
        statsPreview.setOnClickListener { showPage(Page.STATS) }
        home.addView(statsPreview)

        home.addView(groupTitle("App updates"))
        val updateCard = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        updateCard.addView(text("Murmur ${BuildConfig.VERSION_NAME}", 15f, R.color.murmur_text, bold = true))
        updateStatus = text(
            if (BuildConfig.UPDATE_MANIFEST_URL.isBlank()) "Updates are not configured in this build."
            else "Check for a newer signed version.",
            12f, R.color.murmur_muted,
        ).apply { setPadding(0, dp(5), 0, dp(10)) }
        updateCard.addView(updateStatus)
        updateButton = button("Check for updates", primary = false) { onUpdateButton() }.apply {
            isEnabled = BuildConfig.UPDATE_MANIFEST_URL.isNotBlank()
        }
        updateCard.addView(updateButton)
        updateCard.addView(button("Open-source licenses", primary = false) {
            val notice = assets.open("licenses/whisper.cpp.txt").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle("whisper.cpp license")
                .setMessage(notice).setPositiveButton("Close", null).show()
        })
        home.addView(updateCard)

        val speechOverview = card()
        speechRecognitionSummary = destinationRow(speechOverview, "Recognition") { showPage(Page.RECOGNITION) }
        divider(speechOverview)
        speechCleanupSummary = destinationRow(speechOverview, "Transcript cleanup") { showPage(Page.CLEANUP) }
        speech.addView(speechOverview)
        speech.addView(text("Recognition receives audio. Cleanup receives transcript text only when you turn it on.", 12f, R.color.murmur_muted).apply {
            setPadding(dp(4), dp(12), dp(4), 0)
        })

        addSpeechBackLink(recognition)

        speechCard = card().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        val localAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        onDeviceSwitch = Switch(this).apply {
            text = "Use on-device recognition"
            textSize = 15f
            setTextColor(color(R.color.murmur_text))
            isChecked = settings.useOnDeviceRecognition && localAvailable
            isEnabled = localAvailable
            setOnCheckedChangeListener { _, checked ->
                settings.useOnDeviceRecognition = checked
                if (checked) settings.localModelId = null
                refreshLocalModels()
                refreshStatus()
            }
        }
        speechCard.addView(onDeviceSwitch)
        speechCard.addView(text(
            if (localAvailable) "Uses this phone's installed offline speech service. Its model and language support depend on the device."
            else "This phone has no on-device speech service. Use the endpoint below.",
            12f, R.color.murmur_muted,
        ))
        speechCard.addView(fieldLabel("Downloaded models"))
        speechCard.addView(text("Runs on this phone for dictation, lectures and imported audio. Downloading a model uses data and phone storage. Model weights are licensed separately from the app.", 12f, R.color.murmur_muted))
        localModelsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speechCard.addView(localModelsList)
        modelDownloadStatus = text("", 12f, R.color.murmur_muted)
        speechCard.addView(modelDownloadStatus)
        speechCard.addView(fieldLabel("Recognition language"))
        speechLanguageInput = input(settings.speechLanguage, "auto, en, fr…", InputType.TYPE_CLASS_TEXT)
        speechCard.addView(speechLanguageInput)
        speechCard.addView(text("Use auto to detect the language. A language code also applies to the selected online service when supported.", 12f, R.color.murmur_muted))
        translateSwitch = Switch(this).apply {
            text = "Translate speech to English"
            textSize = 15f
            setTextColor(color(R.color.murmur_text))
            isChecked = settings.translateToEnglish
            setOnCheckedChangeListener { _, checked -> settings.translateToEnglish = checked }
        }
        speechCard.addView(translateSwitch)
        speechCard.addView(text("Translation applies to downloaded Whisper models only.", 12f, R.color.murmur_muted))
        onlineChoiceButton = button("Use online service", primary = false) {
            settings.useOnDeviceRecognition = false
            settings.localModelId = null
            refreshLocalModels()
            refreshStatus()
        }
        speechCard.addView(onlineChoiceButton)
        onlineSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        onlineSettings.addView(fieldLabel("Online service"))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            SpeechPreset("OpenRouter", AppSettings.DEFAULT_ENDPOINT, AppSettings.DEFAULT_MODEL),
            SpeechPreset("OpenAI", "https://api.openai.com/v1/audio/transcriptions", "whisper-1"),
            SpeechPreset("Groq", "https://api.groq.com/openai/v1/audio/transcriptions", "whisper-large-v3-turbo"),
        ).forEach { preset ->
            val chip = button(preset.label, primary = false) {
                endpointInput.setText(preset.url)
                modelInput.setText(settings.speechModelFor(preset.url) ?: preset.model)
            }.apply {
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(14), 0, dp(14), 0)
            }
            presetButtons += chip to preset
            presets.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(6)
            })
        }
        onlineSettings.addView(presets)
        onlineSettings.addView(fieldLabel("Transcription URL"))
        endpointInput = input(settings.endpointUrl, "https://…/audio/transcriptions", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        onlineSettings.addView(endpointInput)
        onlineSettings.addView(fieldLabel("Model ID"))
        modelInput = input(settings.model, "whisper-large-v3-turbo", InputType.TYPE_CLASS_TEXT)
        onlineSettings.addView(modelInput)
        onlineSettings.addView(fieldLabel("API key"))
        keyInput = input("", "Paste your key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        onlineSettings.addView(keyInput)
        val keyLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }
        keyStatusView = text("", 12f, R.color.murmur_muted)
        keyLine.addView(keyStatusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        removeKeyButton = button("Remove", primary = false) {
            settings.clearApiKey()
            feedbackView.text = "API key removed."
            refreshStatus()
        }
        keyLine.addView(removeKeyButton)
        onlineSettings.addView(keyLine)
        onlineSettings.addView(button("Save", primary = true) { save() })
        feedbackView = text("", 13f, R.color.murmur_muted).apply { setPadding(0, dp(8), 0, 0) }
        onlineSettings.addView(feedbackView)
        onlineSettings.addView(text("HTTPS is required, except for a server on this phone. The key is encrypted with Android Keystore.", 12f, R.color.murmur_muted).apply {
            setPadding(0, dp(4), 0, 0)
        })
        speechCard.addView(onlineSettings)
        recognition.addView(speechCard)
        refreshLocalModels()
        addSpeechBackLink(cleanup)
        cleanup.addView(text("If you choose an online cleanup service, it receives transcript text and dictionary terms, never audio. Raw text stays in History for recovery.", 13f, R.color.murmur_muted).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val cleanupCard = card().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        cleanupCard.addView(Switch(this).apply {
            text = "Remove filler words"
            textSize = 15f
            setTextColor(color(R.color.murmur_text))
            isChecked = settings.removeFillerWords
            setOnCheckedChangeListener { _, checked -> settings.removeFillerWords = checked }
        })
        cleanupCard.addView(text("Runs on this phone before optional AI cleanup. The original transcript stays in History.", 12f, R.color.murmur_muted))
        cleanupEnabledSwitch = Switch(this).apply {
            text = "Use transcript cleanup"
            textSize = 15f
            setTextColor(color(R.color.murmur_text))
            isChecked = settings.cleanupEnabled
            setOnCheckedChangeListener { _, enabled ->
                if (updatingCleanupSwitch) return@setOnCheckedChangeListener
                if (enabled && !saveCleanup(enableAfterSave = true)) {
                    updatingCleanupSwitch = true
                    isChecked = false
                    updatingCleanupSwitch = false
                } else if (!enabled) {
                    settings.cleanupEnabled = false
                    refreshStatus()
                }
            }
        }
        cleanupCard.addView(cleanupEnabledSwitch)
        cleanupStatus = text("", 12f, R.color.murmur_muted).apply { setPadding(0, dp(2), 0, dp(8)) }
        cleanupCard.addView(cleanupStatus)
        cleanupCard.addView(fieldLabel("Service"))
        val cleanupServices = listOf(
            SpeechPreset("OpenRouter", AppSettings.DEFAULT_CLEANUP_URL, ""),
            SpeechPreset("OpenAI", "https://api.openai.com/v1/chat/completions", ""),
            SpeechPreset("Z.AI", "https://api.z.ai/api/paas/v4/chat/completions", ""),
            SpeechPreset("SiliconFlow", "https://api.siliconflow.com/v1/chat/completions", ""),
            SpeechPreset("Anthropic", "https://api.anthropic.com/v1/messages", ""),
            SpeechPreset("Groq", "https://api.groq.com/openai/v1/chat/completions", ""),
            SpeechPreset("Cerebras", "https://api.cerebras.ai/v1/chat/completions", ""),
            SpeechPreset("AWS Bedrock (Mantle)", "https://bedrock-mantle.us-east-1.api.aws/v1/chat/completions", ""),
            SpeechPreset("Ollama on this phone", "http://127.0.0.1:11434/v1/chat/completions", ""),
            SpeechPreset("Custom URL", "", ""),
        )
        cleanupCard.addView(button("Choose cleanup service", primary = false) {
            AlertDialog.Builder(this).setTitle("Cleanup service")
                .setItems(cleanupServices.map { it.label }.toTypedArray()) { _, index ->
                    val url = cleanupServices[index].url
                    if (url.isNotEmpty()) {
                        cleanupUrlInput.setText(url)
                        cleanupModelInput.setText(settings.cleanupModelFor(url).orEmpty())
                    } else cleanupUrlInput.requestFocus()
                }.setNegativeButton("Cancel", null).show()
        })
        cleanupCard.addView(fieldLabel("Cleanup API URL"))
        cleanupUrlInput = input(settings.cleanupUrl, "https://…/chat/completions", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        cleanupCard.addView(cleanupUrlInput)
        cleanupCard.addView(fieldLabel("Model ID · required"))
        cleanupModelInput = input(settings.cleanupModel, "Your text model", InputType.TYPE_CLASS_TEXT)
        cleanupCard.addView(cleanupModelInput)
        cleanupCard.addView(button("Browse provider models", primary = false) { browseCleanupModels() })
        cleanupCard.addView(text("Choose a text model from your provider, then turn on cleanup. The switch saves these fields for you.", 12f, R.color.murmur_muted).apply {
            setPadding(0, dp(6), 0, 0)
        })
        cleanupCard.addView(fieldLabel("Formality"))
        cleanupFormality = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Very casual", "Casual", "Natural", "Polished", "Formal"))
            setSelection(settings.cleanupFormality)
        }
        cleanupCard.addView(cleanupFormality)
        cleanupCard.addView(fieldLabel("Cleanup instructions"))
        cleanupPromptSummary = text("", 12f, R.color.murmur_muted)
        cleanupCard.addView(cleanupPromptSummary)
        cleanupCard.addView(button("Choose instructions", primary = false) { chooseCleanupPrompt() })
        cleanupCard.addView(button("Create instructions", primary = false) { editCleanupPrompt(null) })
        cleanupCard.addView(button("Edit selected instructions", primary = false) {
            settings.cleanupPrompts.selected?.let(::editCleanupPrompt)
        })
        cleanupCard.addView(button("Delete selected instructions", primary = false) {
            val selected = settings.cleanupPrompts.selected ?: return@button
            AlertDialog.Builder(this).setTitle("Delete ${selected.name}?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    settings.cleanupPrompts.delete(selected.id)
                    refreshStatus()
                }.show()
        })
        cleanupCard.addView(fieldLabel("Cleanup API key"))
        cleanupKeyInput = input("", "Required for OpenRouter", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        cleanupCard.addView(cleanupKeyInput)
        cleanupKeyStatus = text("", 12f, R.color.murmur_muted).apply { setPadding(0, dp(8), 0, dp(8)) }
        cleanupCard.addView(cleanupKeyStatus)
        cleanupCard.addView(button("Save cleanup settings", primary = true) { saveCleanup() })
        cleanupRemoveKeyButton = button("Remove cleanup key", primary = false) {
            settings.clearCleanupKey()
            if (settings.cleanupEnabled && URI(settings.cleanupUrl).host.equals("openrouter.ai", ignoreCase = true)) {
                settings.cleanupEnabled = false
                cleanupFeedback.text = "Key removed. Cleanup is off until you add another key."
            } else cleanupFeedback.text = "Cleanup key removed."
            refreshStatus()
        }
        cleanupCard.addView(cleanupRemoveKeyButton)
        cleanupFeedback = text("", 13f, R.color.murmur_muted).apply { setPadding(0, dp(8), 0, 0) }
        cleanupCard.addView(cleanupFeedback)
        cleanupCard.addView(text("An online cleanup service may charge separately. A local server must run on this phone; localhost is not your computer.", 12f, R.color.murmur_muted).apply {
            setPadding(0, dp(8), 0, 0)
        })
        cleanup.addView(cleanupCard)
        val cleanupEditWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                cleanupStatus.text = "Unsaved changes · turn on to save and enable, or save below."
                cleanupStatus.setTextColor(color(R.color.murmur_muted))
            }
        }
        cleanupUrlInput.addTextChangedListener(cleanupEditWatcher)
        cleanupModelInput.addTextChangedListener(cleanupEditWatcher)
        cleanupKeyInput.addTextChangedListener(cleanupEditWatcher)
        endpointInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshPresets()
        })

        lectures.addView(text("Lecture audio and raw and cleaned text stay on this phone until their retention period ends or you delete them.", 13f, R.color.murmur_muted).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val lectureCard = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        lectureCard.addView(text("New lecture", 17f, R.color.murmur_text, bold = true))
        lectureCard.addView(text("Record a lecture or import audio. Murmur transcribes it with your selected downloaded model or endpoint. An online endpoint may charge for each part.", 12f, R.color.murmur_muted).apply {
            setPadding(0, dp(6), 0, dp(8))
        })
        lectureStatus = text("", 13f, R.color.murmur_muted)
        lectureCard.addView(lectureStatus)
        val lectureActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lectureRecordButton = button("Record lecture", primary = true) { onLectureRecord() }
        lectureActions.addView(lectureRecordButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lectureActions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        lectureActions.addView(button("Import audio", primary = false) { chooseLectureAudio() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lectureCard.addView(lectureActions)
        lectures.addView(lectureCard)
        lectures.addView(groupTitle("Saved lectures"))
        lectureList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lectures.addView(lectureList)
        val latest = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        latestView = text("No transcript yet.", 15f, R.color.murmur_text).apply { setTextIsSelectable(true) }
        latest.addView(latestView)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(button("Copy", primary = false) {
            settings.latestTranscript?.let { transcript ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Murmur transcript", transcript))
            }
        })
        actions.addView(View(this), LinearLayout.LayoutParams(dp(8), 0))
        actions.addView(button("Clear latest", primary = false) {
            settings.latestTranscript = null
            refreshStatus()
        })
        latest.addView(actions)
        transcript.addView(latest)
        rawTranscriptCard = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        rawTranscriptCard.addView(text("RAW TRANSCRIPT", 11f, R.color.murmur_muted, bold = true).apply { letterSpacing = 0.08f })
        rawTranscriptView = text("", 15f, R.color.murmur_text).apply {
            setPadding(0, dp(8), 0, 0)
            setTextIsSelectable(true)
        }
        rawTranscriptCard.addView(rawTranscriptView)
        transcript.addView(rawTranscriptCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        transcript.addView(groupTitle("Storage"))
        retentionButton = button("", primary = false) { showRetentionChoices() }
        transcript.addView(retentionButton)
        transcript.addView(button("Export text history", primary = false) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "murmur-history.json")
            }, HISTORY_EXPORT_REQUEST)
        })
        historyFeedback = text("", 12f, R.color.murmur_muted)
        transcript.addView(historyFeedback)
        transcript.addView(groupTitle("Past dictations"))
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        transcript.addView(historyList)

        words.addView(text("Preferred spelling for names and unusual terms. Used by transcript cleanup when it is on.", 13f, R.color.murmur_muted).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val addWord = card().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        dictionaryInput = input("", "Add a name or term", InputType.TYPE_CLASS_TEXT)
        addWord.addView(dictionaryInput)
        addWord.addView(button("Add word", primary = true) { addDictionaryWord() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        })
        words.addView(addWord)
        words.addView(groupTitle("Saved words"))
        dictionaryList = card()
        words.addView(dictionaryList)
        refreshDictionary()

        inputPage.addView(groupTitle("Voice bubble"))
        inputPage.addView(text("Keep your usual keyboard. The bubble appears beside ordinary text fields on Android 13 and newer. Drag it to move it; tap to dictate.", 13f, R.color.murmur_muted).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val bubbleSettings = card()
        inputBubbleRow = setupRow(bubbleSettings, "Accessibility service") { explainAccessibility() }
        inputPage.addView(bubbleSettings)
        inputPage.addView(text("The bubble stays above the keyboard and remembers where you leave it.", 12f, R.color.murmur_muted).apply {
            setPadding(dp(4), dp(12), dp(4), 0)
        })
        inputPage.addView(text("If Android says Restricted setting, open Settings → Apps → Murmur → ⋮ → Allow restricted settings. Then return to Accessibility and enable Murmur voice bubble. This is a sideloaded-app restriction; signing the APK does not remove it.", 13f, R.color.murmur_muted).apply {
            setPadding(dp(4), dp(12), dp(4), 0)
        })
        inputPage.addView(groupTitle("Audio processing"))
        val noiseAvailable = NoiseSuppressor.isAvailable()
        val noiseCard = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        val noiseToggle = Switch(this).apply {
            text = "Reduce background noise"
            textSize = 15f
            setTextColor(color(R.color.murmur_text))
            isChecked = settings.noiseSuppressionEnabled && noiseAvailable
            isEnabled = noiseAvailable
            setOnCheckedChangeListener { _, checked -> settings.noiseSuppressionEnabled = checked }
        }
        noiseCard.addView(noiseToggle)
        noiseCard.addView(text(
            if (noiseAvailable) "Uses this phone's capture effect when available. It may alter quiet speech."
            else "This phone does not provide a noise suppression effect.",
            12f, R.color.murmur_muted,
        ))
        inputPage.addView(noiseCard)
        inputPage.addView(groupTitle("Murmur keyboard"))
        inputPage.addView(text("A fallback for phones older than Android 13, or if you prefer a keyboard with a mic key.", 13f, R.color.murmur_muted).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val keyboard = card()
        keyboardRow = setupRow(keyboard, "Murmur keyboard") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        divider(keyboard)
        selectedRow = setupRow(keyboard, "Current keyboard") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        inputPage.addView(keyboard)
        showPage(currentPage)
        refreshHistory()
        if (BuildConfig.UPDATE_MANIFEST_URL.isNotBlank()) checkForUpdates(automatic = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", currentPage.name)
        super.onSaveInstanceState(outState)
    }

    private fun showPage(page: Page) {
        currentPage = page
        pageTitle.text = if (page == Page.HOME) "Murmur" else page.label
        pageDescription.text = page.description
        pages.forEach { (section, view) -> view.visibility = if (section == page) View.VISIBLE else View.GONE }
        val selectedTab = when (page) {
            Page.RECOGNITION, Page.CLEANUP -> Page.SPEECH
            Page.LECTURES, Page.STATS -> Page.HOME
            else -> page
        }
        navigationItems.forEach { (section, item) ->
            val selected = section == selectedTab
            item.isSelected = selected
            item.contentDescription = if (selected) "${section.label}, selected" else section.label
            navigationLabels.getValue(section).setTextColor(color(if (selected) R.color.murmur_on_accent else R.color.murmur_muted))
            navigationIcons.getValue(section).imageTintList = ColorStateList.valueOf(color(if (selected) R.color.murmur_on_accent else R.color.murmur_muted))
            val selection = if (selected) GradientDrawable().apply {
                setColor((color(R.color.murmur_accent) and 0x00ffffff) or (242 shl 24))
                cornerRadius = dp(28).toFloat()
            } else null
            item.background = RippleDrawable(
                ColorStateList.valueOf((color(R.color.murmur_accent) and 0x00ffffff) or (32 shl 24)),
                selection,
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(28).toFloat()
                },
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            speechBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            speechBackCallback = if (page == Page.RECOGNITION || page == Page.CLEANUP ||
                page == Page.LECTURES || page == Page.STATS) {
                OnBackInvokedCallback {
                    showPage(if (page == Page.RECOGNITION || page == Page.CLEANUP) Page.SPEECH else Page.HOME)
                }.also {
                    onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
                }
            } else null
        }
        scroll.scrollTo(0, 0)
        dockPreviousOffset = 0
        dockDownwardTravel = 0
        dockUpwardTravel = 0
        setDockExpanded(true)
        if (page == Page.LECTURES) refreshLectures()
        if (page == Page.STATS) refreshProviderCost()
    }

    private fun onPageScroll(scrollY: Int) {
        val maxOffset = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        val offset = scrollY.coerceIn(0, maxOffset)
        if (offset <= dp(40)) {
            dockDownwardTravel = 0
            dockUpwardTravel = 0
            setDockExpanded(true)
        } else if (offset > dockPreviousOffset) {
            dockUpwardTravel = 0
            dockDownwardTravel += offset - dockPreviousOffset
            if (dockDownwardTravel >= dp(20)) {
                dockDownwardTravel = 0
                setDockExpanded(false)
            }
        } else if (offset < dockPreviousOffset) {
            dockDownwardTravel = 0
            dockUpwardTravel += dockPreviousOffset - offset
            if (dockUpwardTravel >= dp(20)) {
                dockUpwardTravel = 0
                setDockExpanded(true)
            }
        }
        dockPreviousOffset = offset
    }

    private fun setDockExpanded(expanded: Boolean) {
        if (dockExpanded == expanded) return
        dockExpanded = expanded
        dockAnimator?.cancel()
        val target = if (expanded) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            applyDockProgress(target)
            return
        }
        dockAnimator = ValueAnimator.ofFloat(dockProgress, target).apply {
            duration = 280
            addUpdateListener { applyDockProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun applyDockProgress(progress: Float) {
        dockProgress = progress
        val screenWidth = resources.configuration.screenWidthDp
        val expandedWidth = dp(minOf(screenWidth - 28, 420))
        val compactWidth = dp(maxOf(228, minOf(screenWidth - 80, 350)))
        navigation.layoutParams = (navigation.layoutParams as FrameLayout.LayoutParams).apply {
            width = compactWidth + ((expandedWidth - compactWidth) * progress).toInt()
            height = dp(52) + (dp(12) * progress).toInt()
        }
        dockBlur?.let { blur ->
            blur.layoutParams = (blur.layoutParams as FrameLayout.LayoutParams).apply {
                width = compactWidth + ((expandedWidth - compactWidth) * progress).toInt()
                height = dp(52) + (dp(12) * progress).toInt()
            }
        }
        navigationItems.values.forEach { item ->
            item.layoutParams = (item.layoutParams as LinearLayout.LayoutParams).apply {
                height = dp(44) + (dp(12) * progress).toInt()
            }
        }
        navigationLabels.values.forEach { label ->
            label.alpha = progress
            label.layoutParams = (label.layoutParams as LinearLayout.LayoutParams).apply {
                height = (dp(16) * progress).toInt()
            }
        }
        scheduleDockBlur()
    }

    private fun scheduleDockBlur() {
        if (dockBlur == null || dockBlurQueued) return
        dockBlurQueued = true
        navigation.postOnAnimation {
            dockBlurQueued = false
            val image = dockBlur ?: return@postOnAnimation
            if (navigation.width == 0 || navigation.height == 0 || scroll.height == 0) return@postOnAnimation
            val now = SystemClock.uptimeMillis()
            if (now - dockBlurLastCapture < 48L) {
                navigation.postDelayed({ scheduleDockBlur() }, 48L - (now - dockBlurLastCapture))
                return@postOnAnimation
            }
            dockBlurLastCapture = now
            val margin = dp(14)
            val width = navigation.width + margin * 2
            val height = navigation.height + margin * 2
            val bitmap = dockBlurBitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { dockBlurBitmap = it }
            val canvas = Canvas(bitmap)
            canvas.drawColor(color(R.color.murmur_background))
            canvas.translate((margin - navigation.left).toFloat(), (margin - navigation.top).toFloat())
            scroll.draw(canvas)
            image.setImageBitmap(bitmap)
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when (currentPage) {
            Page.RECOGNITION, Page.CLEANUP -> showPage(Page.SPEECH)
            Page.LECTURES, Page.STATS -> showPage(Page.HOME)
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            speechBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            speechBackCallback = null
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::statusTitle.isInitialized) {
            refreshStatus()
            refreshHistory()
            refreshLectures()
            refreshLocalModels()
            if (LocalSpeechModels.downloadingId != null) window.decorView.postDelayed(modelProgressRefresh, 1_000)
        }
    }

    override fun onPause() {
        window.decorView.removeCallbacks(modelProgressRefresh)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, lectureReceiver, IntentFilter(LectureService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(lectureReceiver)
        super.onStop()
    }

    private fun onUpdateButton() {
        if (updateBusy) return
        val readyApk = downloadedUpdate
        val update = availableUpdate
        when {
            readyApk != null && readyApk.exists() -> installUpdate(readyApk)
            update != null -> downloadUpdate(update)
            else -> checkForUpdates(automatic = false)
        }
    }

    private fun checkForUpdates(automatic: Boolean) {
        if (updateBusy) return
        updateBusy = true
        updateButton.isEnabled = false
        if (!automatic) updateStatus.text = "Checking for updates…"
        Thread {
            val result = runCatching { UpdateClient(this).check() }
            runOnUiThread {
                updateBusy = false
                result.onSuccess { update ->
                    availableUpdate = update
                    downloadedUpdate = null
                    updateStatus.text = if (update == null) "Murmur is up to date."
                        else "Version ${update.versionName} is available (${update.sizeBytes / 1_000_000} MB)."
                    updateButton.text = if (update == null) "Check for updates" else "Download update"
                }.onFailure { error ->
                    if (!automatic) updateStatus.text = error.message ?: "Could not check for updates."
                }
                updateButton.isEnabled = true
            }
        }.start()
    }

    private fun downloadUpdate(update: AvailableUpdate) {
        if (MurmurReadyService.isActive) {
            AlertDialog.Builder(this).setTitle("Turn off voice capture first")
                .setMessage("Stop the voice bubble before installing an update. Then return here to download it.")
                .setPositiveButton("OK", null).show()
            return
        }
        updateBusy = true
        updateButton.isEnabled = false
        updateStatus.text = "Downloading update…"
        Thread {
            val result = runCatching {
                UpdateClient(this).download(update) { percent ->
                    runOnUiThread { updateStatus.text = "Downloading update… $percent%" }
                }
            }
            runOnUiThread {
                updateBusy = false
                result.onSuccess { file ->
                    downloadedUpdate = file
                    updateStatus.text = "Download verified. Android will ask you to confirm the update."
                    updateButton.text = "Install update"
                    installUpdate(file)
                }.onFailure { error ->
                    updateStatus.text = error.message ?: "Could not download the update."
                    updateButton.text = "Retry download"
                }
                updateButton.isEnabled = true
            }
        }.start()
    }

    private fun installUpdate(apk: File) {
        if (MurmurReadyService.isActive || LectureService.isActive) {
            updateStatus.text = "Finish the voice bubble or lecture before installing."
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("Allow Murmur to install updates")
                .setMessage("Android needs your permission to install a downloaded Murmur APK. Return here and tap Install update afterwards.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }.show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            updateStatus.text = "Android could not open the installer."
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // the keyboard picker is a dialog, so resume never fires after it
        if (hasFocus && ::statusTitle.isInitialized) refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST && pendingReadyStart) {
            pendingReadyStart = false
            beginVoiceReady()
        }
        if (requestCode == MICROPHONE_REQUEST && pendingLectureStart) {
            pendingLectureStart = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginLectureRecording()
            else lectureStatus.text = "Microphone permission is needed to record a lecture."
        }
        refreshStatus()
    }

    @Deprecated("Uses the platform document picker without an added activity-result dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == LECTURE_IMPORT_REQUEST) {
            val uri = data?.data ?: return
            if (!lectureEndpointReady()) return
            runCatching {
                val title = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.substringBeforeLast('.')?.takeIf(String::isNotBlank) ?: "Imported audio"
                LectureService.import(this, uri, title)
            }.onFailure { lectureStatus.text = it.message ?: "Could not open audio file." }
            refreshLectures()
            return
        }
        if (requestCode != HISTORY_EXPORT_REQUEST) return
        val uri = data?.data ?: return
        historyFeedback.text = "Exporting history…"
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer -> history.writeExport(writer) }
                } ?: error("Could not open the selected file.")
            }
            runOnUiThread {
                historyFeedback.text = if (result.isSuccess) "Text history exported."
                    else "Could not export history. Choose another location."
            }
        }.start()
    }

    private fun explainAccessibility() {
        if (Build.VERSION.SDK_INT < 33) {
            showPage(Page.INPUT)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Enable Murmur voice bubble")
            .setMessage(
                "Murmur uses Android Accessibility to detect when an ordinary text field is focused and insert your dictation at the cursor while your usual keyboard stays active. " +
                    "It checks the field type and cursor context for safe placement and spacing, but does not upload text already in the field. " +
                    "The bubble stays hidden in password and other sensitive fields. " +
                    "Recording starts only when you tap the bubble. Audio follows the recognition choice you make in Models. " +
                    "If Android blocks the switch, open Settings → Apps → Murmur → ⋮ → Allow restricted settings, then return to Accessibility."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Agree and open Settings") { _, _ ->
                getSharedPreferences("murmur", MODE_PRIVATE).edit()
                    .putBoolean("bubble_disclosure_accepted", true).apply()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun startVoiceReady() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        if (!isBubbleEnabled()) {
            explainAccessibility()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("murmur", MODE_PRIVATE).getBoolean("notification_permission_requested", false)
        ) {
            pendingReadyStart = true
            getSharedPreferences("murmur", MODE_PRIVATE).edit()
                .putBoolean("notification_permission_requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        beginVoiceReady()
    }

    private fun beginVoiceReady() {
        try {
            MurmurReadyService.start(this)
        } catch (_: Exception) {
            feedbackView.text = "Voice ready could not start. Open Murmur and try again."
        }
        window.decorView.postDelayed({ refreshStatus() }, 400)
    }

    private fun isBubbleEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < 33 ||
            !getSharedPreferences("murmur", MODE_PRIVATE).getBoolean("bubble_disclosure_accepted", false)
        ) return false
        val component = ComponentName(this, MurmurAccessibilityService::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private fun save() {
        try {
            val previousUrl = settings.endpointUrl
            settings.speechLanguage = speechLanguageInput.text.toString()
            settings.saveEndpoint(endpointInput.text.toString(), modelInput.text.toString())
            settings.localModelId = null
            settings.useOnDeviceRecognition = false
            val key = keyInput.text.toString()
            if (key.isNotBlank()) settings.saveApiKey(key.trim())
            keyInput.text.clear()
            feedbackView.text = if (settings.endpointUrl != previousUrl && key.isBlank() && !settings.hasApiKey) {
                "Saved. Add a key for this service if it needs one."
            } else "Saved."
        } catch (error: IllegalArgumentException) {
            feedbackView.text = error.message ?: "Check the URL and model."
        } catch (_: Exception) {
            feedbackView.text = "Could not save the key. Enter it again and retry."
        }
        refreshStatus()
        refreshLocalModels()
    }

    private fun refreshLocalModels() {
        if (!::localModelsList.isInitialized) return
        val downloadingId = LocalSpeechModels.downloadingId
        modelDownloadStatus.text = if (downloadingId != null) {
            val label = LocalSpeechModels.catalog.firstOrNull { it.id == downloadingId }?.label ?: "model"
            "Downloading $label · ${LocalSpeechModels.downloadPercent}%"
        } else LocalSpeechModels.downloadMessage.orEmpty()
        modelDownloadStatus.visibility = if (modelDownloadStatus.text.isEmpty()) View.GONE else View.VISIBLE
        localModelsList.removeAllViews()
        LocalSpeechModels.catalog.forEach { model ->
            val installed = LocalSpeechModels.isInstalled(this, model)
            val supported = LocalSpeechModels.canRun(this, model)
            val resumable = File(LocalSpeechModels.file(this, model).absolutePath + ".part").length() > 0
            val active = settings.localModelId == model.id
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            row.addView(text(model.label + when {
                active -> " · selected"
                !supported -> " · needs ${model.minimumRamGb} GB RAM"
                else -> ""
            }, 14f, R.color.murmur_text, bold = active))
            val downloading = downloadingId == model.id
            row.addView(button(if (downloading) "Cancel download" else if (installed) if (active) "Selected" else "Use this model" else if (resumable) "Resume download" else "Download", primary = active) {
                if (downloading) {
                    LocalSpeechModels.cancelDownload()
                    modelDownloadStatus.text = "Cancelling download…"
                    return@button
                }
                if (installed) {
                    settings.localModelId = model.id
                    refreshLocalModels()
                    refreshStatus()
                } else if (downloadingId == null) {
                    LocalSpeechModels.startDownload(this, model)
                    refreshLocalModels()
                    window.decorView.removeCallbacks(modelProgressRefresh)
                    window.decorView.postDelayed(modelProgressRefresh, 1_000)
                }
            }.apply { isEnabled = downloading || (downloadingId == null && !active && supported) })
            if (installed) row.addView(button("Delete model", primary = false) {
                AlertDialog.Builder(this).setTitle("Delete ${model.label}?")
                    .setMessage("The model will need to be downloaded again before it can transcribe audio.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        if (active) settings.localModelId = null
                        LocalSpeechModels.file(this, model).delete()
                        refreshLocalModels()
                        refreshStatus()
                    }.show()
            })
            localModelsList.addView(row)
        }
        if (::onlineSettings.isInitialized) {
            val online = !settings.useOnDeviceRecognition && settings.localModelId == null
            onlineSettings.visibility = if (online) View.VISIBLE else View.GONE
            onlineChoiceButton.visibility = if (online) View.GONE else View.VISIBLE
            onDeviceSwitch.isChecked = settings.useOnDeviceRecognition
        }
    }

    private fun saveCleanup(enableAfterSave: Boolean = false): Boolean {
        try {
            val url = try { AppSettings.parseCleanupUrl(cleanupUrlInput.text.toString()) }
                catch (error: IllegalArgumentException) {
                    cleanupUrlInput.error = error.message
                    throw error
                }
            val model = cleanupModelInput.text.toString().trim()
            if (model.isBlank()) {
                cleanupModelInput.error = "Enter a model ID"
                throw IllegalArgumentException("Add a cleanup model ID below to turn this on.")
            }
            val endpointChanged = url.toASCIIString() != settings.cleanupUrl
            val key = cleanupKeyInput.text.toString().trim()
            val hasKey = key.isNotEmpty() || settings.hasCleanupKeyFor(url.toASCIIString())
            if (enableAfterSave && url.host.equals("openrouter.ai", ignoreCase = true) && !hasKey) {
                cleanupKeyInput.error = "API key required"
                throw IllegalArgumentException("Add a cleanup API key below to use OpenRouter.")
            }
            if (endpointChanged) settings.cleanupEnabled = false
            settings.saveCleanupEndpoint(
                url.toASCIIString(),
                model,
                cleanupFormality.selectedItemPosition,
            )
            if (key.isNotBlank()) settings.saveCleanupKey(key)
            cleanupKeyInput.text.clear()
            settings.cleanupEnabled = enableAfterSave || (settings.cleanupEnabled && !endpointChanged &&
                (!url.host.equals("openrouter.ai", ignoreCase = true) || hasKey))
            settings.lastCleanupFailure = null
            cleanupFeedback.text = if (enableAfterSave) "Saved and turned on." else if (endpointChanged) {
                "Saved. Cleanup is off because the service changed."
            } else "Cleanup settings saved."
            refreshStatus()
            return true
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "Check the cleanup settings."
            cleanupStatus.text = message
            cleanupStatus.setTextColor(color(R.color.murmur_error))
            cleanupFeedback.text = message
        } catch (_: Exception) {
            cleanupStatus.text = "Could not save the cleanup key. Enter it again and retry."
            cleanupStatus.setTextColor(color(R.color.murmur_error))
            cleanupFeedback.text = cleanupStatus.text
        }
        return false
    }

    private fun browseCleanupModels() {
        val url = try { AppSettings.parseCleanupUrl(cleanupUrlInput.text.toString()) }
            catch (error: IllegalArgumentException) {
                cleanupFeedback.text = error.message
                return
            }
        cleanupFeedback.text = "Loading models…"
        Thread({
            val result = runCatching { CleanupModelCatalog.fetch(url, settings.cleanupKeyFor(url.toASCIIString())) }
            runOnUiThread {
                result.onSuccess { models ->
                    if (models.isEmpty()) cleanupFeedback.text = "No models returned. You can enter a model ID manually."
                    else {
                        cleanupFeedback.text = "Choose a model."
                        AlertDialog.Builder(this).setTitle("Cleanup models")
                            .setItems(models.toTypedArray()) { _, index -> cleanupModelInput.setText(models[index]) }
                            .setNegativeButton("Cancel", null).show()
                    }
                }.onFailure { error -> cleanupFeedback.text = error.message ?: "Could not load models. Enter an ID manually." }
            }
        }, "murmur-cleanup-models").start()
    }

    private fun chooseCleanupPrompt() {
        val prompts = settings.cleanupPrompts.all
        val labels = arrayOf("Default instructions") + prompts.map { it.name }
        AlertDialog.Builder(this).setTitle("Cleanup instructions")
            .setItems(labels) { _, index ->
                settings.cleanupPrompts.select(if (index == 0) null else prompts[index - 1].id)
                refreshStatus()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editCleanupPrompt(existing: CleanupPrompt?) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val name = input(existing?.name.orEmpty(), "Name", InputType.TYPE_CLASS_TEXT)
        val instructions = input(existing?.instructions.orEmpty(), "How should Murmur clean text?",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 5
            gravity = Gravity.TOP
        }
        fields.addView(name)
        fields.addView(instructions)
        AlertDialog.Builder(this).setTitle(if (existing == null) "Create instructions" else "Edit instructions")
            .setView(fields).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                runCatching { settings.cleanupPrompts.save(existing?.id, name.text.toString(), instructions.text.toString()) }
                    .onFailure { cleanupFeedback.text = it.message ?: "Could not save instructions." }
                refreshStatus()
            }.show()
    }

    private fun addDictionaryWord() {
        try {
            settings.addDictionaryTerm(dictionaryInput.text.toString())
            dictionaryInput.text.clear()
            refreshDictionary()
        } catch (error: IllegalArgumentException) {
            dictionaryInput.error = error.message
        }
    }

    private fun refreshDictionary() {
        dictionaryList.removeAllViews()
        val terms = settings.dictionary
        if (terms.isEmpty()) {
            dictionaryList.addView(text("No words saved yet.", 14f, R.color.murmur_muted).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }
        terms.forEachIndexed { index, term ->
            if (index > 0) divider(dictionaryList)
            val row = row()
            row.addView(text(term, 15f, R.color.murmur_text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Remove", primary = false) {
                settings.removeDictionaryTerm(term)
                refreshDictionary()
            })
            dictionaryList.addView(row)
        }
    }

    private fun refreshHistory() {
        if (!::historyList.isInitialized) return
        retentionButton.text = when (settings.historyRetentionDays) {
            7 -> "Keep history for 7 days"
            30 -> "Keep history for 30 days"
            90 -> "Keep history for 90 days"
            else -> "Keep history until deleted"
        }
        val summary = runCatching { history.stats() }.getOrNull()
        renderStats(summary)
        historyList.removeAllViews()
        val records = runCatching { history.recent() }.getOrNull()
        if (records == null) {
            homeLatestView.text = "History could not be loaded."
            homeLatestDateView.visibility = View.GONE
            homeLatestLinkView.text = "Open history  ↗"
            homeRecentCard.contentDescription = "Open history"
            historyList.addView(text("History could not be loaded.", 14f, R.color.murmur_muted))
            return
        }
        val latest = records.firstOrNull()
        homeLatestView.text = latest?.finalText ?: "Your first dictation will appear here."
        homeLatestDateView.text = latest?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.timestamp))
        }.orEmpty()
        homeLatestDateView.visibility = if (latest == null) View.GONE else View.VISIBLE
        homeLatestLinkView.text = if (latest == null) "Open history  ↗" else "View transcript  ↗"
        homeRecentCard.contentDescription = if (latest == null) "Open history" else "View latest transcript"
        if (records.isEmpty()) {
            historyList.addView(card().apply {
                setPadding(dp(16), dp(18), dp(16), dp(18))
                addView(text("No past dictations yet.", 14f, R.color.murmur_muted))
            })
            return
        }
        records.forEach { record ->
            val entry = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
            entry.addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.timestamp)),
                12f, R.color.murmur_muted))
            entry.addView(text(record.finalText, 15f, R.color.murmur_text).apply {
                setPadding(0, dp(7), 0, dp(10))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button("View", primary = false) {
                val detail = if (record.raw == record.finalText) record.finalText
                    else "${record.finalText}\n\nRaw transcript\n${record.raw}"
                AlertDialog.Builder(this).setTitle("Dictation").setMessage(detail)
                    .setPositiveButton("Close", null).show()
            })
            actions.addView(button("Copy", primary = false) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Murmur transcript", record.finalText))
            })
            actions.addView(button("Delete", primary = false) {
                AlertDialog.Builder(this).setTitle("Delete dictation?")
                    .setMessage("This removes the raw and cleaned text from this phone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        history.delete(record.id)
                        if (settings.latestHistoryId == record.id) {
                            settings.latestTranscript = null
                            refreshStatus()
                        }
                        refreshHistory()
                    }.show()
            })
            entry.addView(actions)
            historyList.addView(entry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun lectureEndpointReady(): Boolean {
        val ready = runCatching { !settings.useOnDeviceRecognition && settings.activeEndpointOrNull() != null }.getOrDefault(false)
        if (!ready) {
            AlertDialog.Builder(this).setTitle("Choose a speech model or endpoint")
                .setMessage("Lectures and audio files use a downloaded model or a transcription endpoint. The phone's installed speech service cannot process saved audio.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Open Recognition") { _, _ -> showPage(Page.RECOGNITION) }
                .show()
        }
        return ready
    }

    private fun onLectureRecord() {
        if (LectureService.isActive) {
            LectureService.finish(this)
            lectureStatus.text = "Finishing recording…"
            return
        }
        if (!lectureEndpointReady()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLectureStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        beginLectureRecording()
    }

    private fun beginLectureRecording() {
        MurmurReadyService.stop(this)
        runCatching { LectureService.record(this) }
            .onFailure { lectureStatus.text = it.message ?: "Could not start recording." }
        lectureStatus.text = "Starting lecture recording…"
    }

    private fun chooseLectureAudio() {
        if (LectureService.isActive) {
            lectureStatus.text = "Finish the current lecture first."
            return
        }
        if (!lectureEndpointReady()) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }, LECTURE_IMPORT_REQUEST)
    }

    private fun refreshLectures() {
        if (!::lectureList.isInitialized) return
        lectureStatus.text = LectureService.status
        homeLectureStatus.text = if (LectureService.isActive) LectureService.status else ""
        lectureRecordButton.text = if (LectureService.isActive && LectureService.status.startsWith("Recording"))
            "Finish recording" else "Record lecture"
        lectureRecordButton.isEnabled = !LectureService.isActive || LectureService.status.startsWith("Recording")
        homeLectureRecordButton.text = lectureRecordButton.text
        homeLectureRecordButton.isEnabled = lectureRecordButton.isEnabled
        lectureList.removeAllViews()
        val records = runCatching { history.lectures() }.getOrElse {
            lectureList.addView(text("Saved lectures could not be loaded.", 13f, R.color.murmur_muted))
            return
        }
        if (records.isEmpty()) {
            lectureList.addView(text("No lectures yet.", 13f, R.color.murmur_muted))
            return
        }
        records.forEach { record ->
            val entry = card().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
            entry.addView(text(record.title, 15f, R.color.murmur_text, bold = true))
            val state = if (!LectureService.isActive && record.status in listOf("recording", "processing"))
                "Interrupted · retry" else record.status.replaceFirstChar(Char::uppercase)
            val minutes = record.durationMs / 60_000
            entry.addView(text("$state · ${minutes} min · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.timestamp))}",
                12f, R.color.murmur_muted))
            if (record.finalText.isNotBlank()) entry.addView(text(record.finalText, 14f, R.color.murmur_text).apply {
                setPadding(0, dp(8), 0, dp(8))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
            val textActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            textActions.addView(button("View text", primary = false) {
                val body = if (record.raw == record.finalText) record.finalText
                    else "${record.finalText}\n\nRaw transcript\n${record.raw}"
                AlertDialog.Builder(this).setTitle(record.title).setMessage(body.ifBlank { "No transcript yet." })
                    .setPositiveButton("Close", null).show()
            })
            textActions.addView(button("Copy", primary = false) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Murmur lecture", record.finalText.ifBlank { record.raw }))
            })
            entry.addView(textActions)
            val audioActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            audioActions.addView(button("Share audio", primary = false) {
                val file = history.lectureFile(record.fileName)
                if (!file.exists()) {
                    lectureStatus.text = "Saved audio is missing."
                } else {
                    val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "audio/wav"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share lecture audio"))
                }
            })
            val manageActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            manageActions.addView(button("Retry", primary = false) {
                if (LectureService.isActive) lectureStatus.text = "Finish the current lecture first."
                else if (lectureEndpointReady()) AlertDialog.Builder(this)
                    .setTitle("Retry transcription?")
                    .setMessage("Murmur will send this audio to your selected endpoint again. Your endpoint may charge for the new requests. The current transcript stays available until new text arrives.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Retry") { _, _ ->
                        runCatching { LectureService.retry(this, record.id) }
                            .onFailure { lectureStatus.text = it.message ?: "Could not retry." }
                    }.show()
            })
            manageActions.addView(button("Delete", primary = false) {
                if (LectureService.isActive) {
                    lectureStatus.text = "Finish the current lecture first."
                } else AlertDialog.Builder(this).setTitle("Delete lecture?")
                    .setMessage("This removes the saved audio, raw transcript and cleaned transcript from this phone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> history.deleteLecture(record.id); refreshLectures() }
                    .show()
            })
            audioActions.addView(manageActions)
            entry.addView(audioActions)
            lectureList.addView(entry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    private fun renderStats(stats: DictationStats?) {
        val hasHistory = (stats?.dictations ?: 0) > 0
        val numbers = NumberFormat.getIntegerInstance()
        homeStatsSummary.text = stats?.let { "${numbers.format(it.thisWeekWords)} words this week" }
            ?: "Stats are unavailable right now"
        statsTotalView.text = stats?.let { numbers.format(it.totalWords) } ?: "—"
        statsWordsView.text = stats?.let { numbers.format(it.thisWeekWords) } ?: "—"
        statsPaceView.text = stats?.recordedWpm?.let { "${numbers.format(it)} wpm" } ?: "—"
        statsMinutesView.text = stats?.takeIf { it.recordingsWithDuration > 0 }?.let {
            "${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it.recordedMinutes)} min"
        } ?: "—"
        statsTrendView.text = when {
            stats == null -> "Statistics are unavailable right now"
            !hasHistory -> "Your first dictation will start the weekly chart."
            stats.weekOverWeekPercent == null -> "No previous week to compare"
            else -> {
                val change = stats.weekOverWeekPercent
                val sign = if (change > 0) "+" else ""
                "$sign${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }.format(change)}% vs last full week"
            }
        }
        statsMethodView.text = when {
            stats == null -> "Try again after Murmur has finished starting up."
            stats.recordingsWithDuration == 0 -> "No recorded durations yet, so pace and minutes cannot be calculated. This week is still in progress."
            else -> "Pace uses ${stats.recordingsWithDuration} of ${stats.dictations} dictations with recorded duration and includes pauses. This week is still in progress."
        }
        weeklyWordsChart.visibility = if (hasHistory) View.VISIBLE else View.GONE
        weeklyPaceTitle.visibility = if (hasHistory) View.VISIBLE else View.GONE
        weeklyPaceRow.visibility = if (hasHistory) View.VISIBLE else View.GONE
        statsMethodView.visibility = if (hasHistory) View.VISIBLE else View.GONE

        weeklyWordsChart.removeAllViews()
        weeklyPaceRow.removeAllViews()
        if (stats == null) return
        val highestWeek = stats.weeks.maxOfOrNull { it.words }?.coerceAtLeast(1) ?: 1
        val dateFormat = SimpleDateFormat("M/d", Locale.getDefault())
        stats.weeks.forEachIndexed { index, week ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            column.contentDescription = "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(week.weekStart))}: ${week.words} words"
            column.addView(text(if (week.words == 0) "" else compactCount(week.words), 10f, R.color.murmur_muted).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
            val barArea = FrameLayout(this)
            val barHeight = if (week.words == 0) dp(3) else dp((week.words * 68.0 / highestWeek).toInt().coerceAtLeast(6))
            val barColor = when {
                week.words == 0 -> color(R.color.murmur_border)
                index == stats.weeks.lastIndex -> color(R.color.murmur_accent)
                else -> (color(R.color.murmur_accent) and 0x00ffffff) or (145 shl 24)
            }
            barArea.addView(View(this).apply {
                background = GradientDrawable().apply { setColor(barColor); cornerRadius = dp(3).toFloat() }
            }, FrameLayout.LayoutParams(dp(19), barHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            column.addView(barArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
            column.addView(text(dateFormat.format(Date(week.weekStart)), 10f, R.color.murmur_muted).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))
            weeklyWordsChart.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            weeklyPaceRow.addView(text(week.recordedWpm?.toString() ?: "—", 10f, R.color.murmur_muted).apply {
                gravity = Gravity.CENTER
                contentDescription = "${dateFormat.format(Date(week.weekStart))}: " +
                    (week.recordedWpm?.let { "$it words per minute" } ?: "no recorded pace")
            }, LinearLayout.LayoutParams(0, dp(24), 1f))
        }
    }

    private fun costRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 13f, R.color.murmur_muted),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = text("—", 15f, R.color.murmur_text, bold = true)
        row.addView(value)
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        return value
    }

    private fun refreshProviderCost() {
        if (costLoading) return
        costLoading = true
        costRefreshButton.isEnabled = false
        costStatus.text = "Checking OpenRouter usage…"
        Thread {
            val result = runCatching { OpenRouterUsageClient.fetch(settings.openRouterUsageKeys()) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                costLoading = false
                costRefreshButton.isEnabled = true
                result.onSuccess { usage ->
                    costRows.visibility = if (usage.keyCount == 0) View.GONE else View.VISIBLE
                    costStatus.text = if (usage.keyCount == 0)
                        "Add an OpenRouter key in Models or Cleanup to see provider usage."
                    else "Across ${usage.keyCount} configured OpenRouter ${if (usage.keyCount == 1) "key" else "keys"}."
                    costWeekView.text = formatUsd(usage.weekUsd)
                    costMonthView.text = formatUsd(usage.monthUsd)
                    costAllView.text = formatUsd(usage.allTimeUsd)
                    costByokNote.text = if (usage.byokMonthUsd > 0.0)
                        "BYOK model usage this month: ${formatUsd(usage.byokMonthUsd)}. Your provider bills this separately."
                    else ""
                }.onFailure {
                    costStatus.text = "Could not check OpenRouter usage. Check your connection and key, then retry."
                    costRows.visibility = View.GONE
                    costByokNote.text = ""
                }
            }
        }.start()
    }

    private fun formatUsd(value: Double): String {
        if (value > 0.0 && value < 0.000001) return "<\$0.000001"
        return NumberFormat.getCurrencyInstance().apply {
            currency = Currency.getInstance("USD")
            minimumFractionDigits = 2
            maximumFractionDigits = 6
        }.format(value)
    }

    private fun compactCount(count: Int): String = if (count < 1_000) count.toString()
        else String.format(Locale.getDefault(), "%.1fk", count / 1_000.0)

    private fun showRetentionChoices() {
        val choices = arrayOf("Until deleted", "7 days", "30 days", "90 days")
        val days = intArrayOf(0, 7, 30, 90)
        AlertDialog.Builder(this).setTitle("Keep text history")
            .setSingleChoiceItems(choices, days.indexOf(settings.historyRetentionDays)) { dialog, which ->
                dialog.dismiss()
                val selected = days[which]
                if (selected == settings.historyRetentionDays) return@setSingleChoiceItems
                if (selected == 0) {
                    settings.historyRetentionDays = 0
                    refreshHistory()
                } else {
                    AlertDialog.Builder(this).setTitle("Change history retention?")
                        .setMessage("Dictations older than ${choices[which]} will be deleted from this phone now and after future recordings.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete older entries") { _, _ ->
                            settings.historyRetentionDays = selected
                            history.pruneOlderThan(selected)
                            if (settings.latestHistoryId?.let { !history.contains(it) } == true) {
                                settings.latestTranscript = null
                                refreshStatus()
                            }
                            refreshHistory()
                        }.show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun refreshStatus() {
        val micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val keyboardEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val keyboardSelected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        val host = runCatching { URI(settings.endpointUrl).host }.getOrNull() ?: settings.endpointUrl
        val onPhone = host == "localhost" || host == "127.0.0.1"
        val localRecognition = settings.useOnDeviceRecognition && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val localModel = settings.localModelId?.takeIf { id ->
            LocalSpeechModels.catalog.firstOrNull { it.id == id }?.let {
                LocalSpeechModels.isInstalled(this, it) && LocalSpeechModels.canRun(this, it)
            } == true
        }
        val keyDone = localRecognition || localModel != null || settings.hasApiKey || onPhone
        val bubbleSupported = Build.VERSION.SDK_INT >= 33
        val bubbleEnabled = isBubbleEnabled()
        val voiceReady = MurmurReadyService.isActive

        inputBubbleRow.update(bubbleEnabled, when {
            !bubbleSupported -> "Available on Android 13 and newer"
            bubbleEnabled -> "Enabled"
            else -> "Open Accessibility settings to enable"
        }, "Enable", hidden = !bubbleSupported)
        keyboardRow.update(keyboardEnabled, if (keyboardEnabled) "Enabled" else "Off", "Enable")
        selectedRow.update(keyboardSelected, if (keyboardSelected) "Murmur keyboard" else "Your usual keyboard", "Switch", neutral = true)

        // one obvious next step, in the order setup has to happen
        val next: Triple<String, String, () -> Unit> = when {
            !micAllowed -> Triple("Allow the microphone to start", "Allow microphone") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            }
            bubbleSupported && !bubbleEnabled -> Triple("Turn on the voice bubble", "Enable voice bubble") { explainAccessibility() }
            !keyDone -> Triple("Add your API key", "Add API key") { focusKeyInput() }
            bubbleSupported && !voiceReady -> Triple("Voice bubble is off", "Turn on voice bubble") { startVoiceReady() }
            bubbleSupported -> Triple("Ready to dictate in another app", "Turn off voice bubble") {
                MurmurReadyService.stop(this)
                window.decorView.postDelayed({ refreshStatus() }, 400)
            }
            !keyboardEnabled -> Triple("Enable the Murmur keyboard", "Open keyboard settings") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            !keyboardSelected -> Triple("Switch to the Murmur keyboard", "Choose keyboard") {
                imm.showInputMethodPicker()
            }
            else -> Triple("Ready. Tap Mic on the Murmur keyboard", "", {})
        }
        statusTitle.text = next.first
        heroAction = next.third
        heroButton.text = next.second
        heroButton.visibility = if (next.second.isEmpty()) View.GONE else View.VISIBLE
        styleButton(heroButton, primary = !(bubbleSupported && voiceReady && micAllowed && bubbleEnabled && keyDone))
        val audioDestination = if (localRecognition || localModel != null || onPhone)
            "Audio stays on this phone." else "Audio is sent to $host for transcription."
        statusDetail.text = if (bubbleSupported && voiceReady && bubbleEnabled && micAllowed && keyDone)
            "Open a text field, tap the bubble to record, then tap it again to finish. $audioDestination"
        else audioDestination
        speechRecognitionSummary.text = when {
            localRecognition -> "Phone speech service"
            localModel != null -> "Downloaded · $localModel"
            else -> "$host · ${settings.model}"
        }
        val cleanupHost = runCatching { URI(settings.cleanupUrl).host }.getOrNull() ?: "your service"
        speechCleanupSummary.text = when {
            settings.cleanupEnabled && settings.lastCleanupFailure != null -> "On · last cleanup failed; local cleanup used"
            settings.cleanupEnabled -> "On · $cleanupHost · ${settings.cleanupModel}"
            settings.cleanupModel.isBlank() -> "Off · add a model to enable"
            cleanupHost.equals("openrouter.ai", ignoreCase = true) && !settings.hasCleanupKey -> "Off · add a cleanup key"
            else -> "Off · ready to turn on"
        }
        updatingCleanupSwitch = true
        cleanupEnabledSwitch.isChecked = settings.cleanupEnabled
        updatingCleanupSwitch = false
        cleanupStatus.setTextColor(color(R.color.murmur_muted))
        cleanupStatus.text = when {
            settings.cleanupEnabled -> "On · $cleanupHost · ${settings.cleanupModel}"
            settings.cleanupModel.isBlank() -> "Off · add a model ID below, then turn this on."
            cleanupHost.equals("openrouter.ai", ignoreCase = true) && !settings.hasCleanupKey ->
                "Off · add a cleanup API key below, then turn this on."
            else -> "Off · ready to turn on."
        }
        if (settings.cleanupEnabled && settings.lastCleanupFailure != null) {
            cleanupStatus.text = "${cleanupStatus.text}\n${settings.lastCleanupFailure}"
            cleanupStatus.setTextColor(color(R.color.murmur_error))
        }

        keyStatusView.text = if (settings.hasApiKey) "A key is saved for $host" else "No key saved"
        removeKeyButton.visibility = if (settings.hasApiKey) View.VISIBLE else View.GONE
        latestView.text = settings.latestTranscript ?: "No transcript yet."
        rawTranscriptView.text = settings.latestRawTranscript.orEmpty()
        rawTranscriptCard.visibility = if (settings.latestRawTranscript != null &&
            settings.latestRawTranscript != settings.latestTranscript) View.VISIBLE else View.GONE
        cleanupKeyStatus.text = if (settings.hasCleanupKey) "Cleanup key saved separately from speech" else "No cleanup key saved"
        cleanupPromptSummary.text = settings.cleanupPrompts.selected?.let { "Using ${it.name}" } ?: "Using default instructions"
        cleanupRemoveKeyButton.visibility = if (settings.hasCleanupKey) View.VISIBLE else View.GONE
        refreshPresets()
    }

    private fun refreshPresets() {
        val current = endpointInput.text.toString().trim()
        presetButtons.forEach { (chip, preset) ->
            val selected = current == preset.url
            chip.background = RippleDrawable(
                ColorStateList.valueOf(color(R.color.murmur_border)),
                rounded(R.color.murmur_background, 8f, stroke = if (selected) R.color.murmur_accent else R.color.murmur_border),
                null,
            )
            chip.setTextColor(color(if (selected) R.color.murmur_accent else R.color.murmur_text))
            chip.text = if (selected) "✓  ${preset.label}" else preset.label
            chip.contentDescription = "${preset.label}${if (selected) ", selected" else ""}"
        }
    }


    private fun focusKeyInput() {
        showPage(Page.RECOGNITION)
        keyInput.requestFocus()
        scroll.post {
            val field = Rect(0, 0, keyInput.width, keyInput.height)
            scroll.offsetDescendantRectToMyCoords(keyInput, field)
            scroll.smoothScrollTo(0, field.top - dp(48))
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(keyInput, 0)
    }

    private inner class SetupRow(val detail: TextView, val action: Button) {
        fun update(done: Boolean, detailText: String, actionText: String, hidden: Boolean = false, neutral: Boolean = false) {
            detail.text = if (done && !neutral) "✓  $detailText" else detailText
            detail.setTextColor(color(if (done && !neutral) R.color.murmur_accent else R.color.murmur_muted))
            action.text = actionText
            action.visibility = if (done || hidden) View.GONE else View.VISIBLE
        }
    }

    private fun setupRow(parent: LinearLayout, title: String, onAction: () -> Unit): SetupRow {
        val row = row()
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 15f, R.color.murmur_text))
        val detail = text("", 13f, R.color.murmur_muted)
        labels.addView(detail)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // secondary on purpose: the status card carries the one primary action
        val action = button("", primary = false, onAction)
        row.addView(action)
        parent.addView(row)
        return SetupRow(detail, action)
    }

    private fun destinationRow(parent: LinearLayout, title: String, onOpen: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "Open $title"
            setOnClickListener { onOpen() }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 15f, R.color.murmur_text, bold = true))
        val detail = text("", 12f, R.color.murmur_muted).apply { setPadding(0, dp(3), 0, 0) }
        labels.addView(detail)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("›", 24f, R.color.murmur_muted).apply { gravity = Gravity.CENTER })
        parent.addView(row)
        return detail
    }

    private fun addSpeechBackLink(page: LinearLayout) {
        page.addView(text("‹  Models", 14f, R.color.murmur_accent, bold = true).apply {
            setPadding(dp(4), 0, dp(4), dp(15))
            isClickable = true
            isFocusable = true
            setOnClickListener { showPage(Page.SPEECH) }
        })
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(16), dp(8), dp(12), dp(8))
    }

    private fun statsCell(label: String): Pair<LinearLayout, TextView> {
        val value = text("—", 24f, R.color.murmur_text, bold = true).apply {
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(10), dp(13))
            addView(text(label, 11f, R.color.murmur_muted))
            addView(value)
        }
        return cell to value
    }

    private fun statsRow(left: LinearLayout, right: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(View(this@MainActivity).apply { setBackgroundColor(color(R.color.murmur_border)) },
            LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(R.color.murmur_surface, 12f, stroke = R.color.murmur_border)
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(color(R.color.murmur_border)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private fun groupTitle(value: String): TextView = text(value.uppercase(), 12f, R.color.murmur_muted).apply {
        letterSpacing = 0.06f
        setPadding(dp(4), dp(24), dp(4), dp(8))
    }

    private fun fieldLabel(value: String): TextView = text(value, 14f, R.color.murmur_text).apply {
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun text(value: String, size: Float, colorRes: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(colorRes))
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun input(value: String, hint: String, type: Int): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        inputType = type
        isSingleLine = true
        textSize = 15f
        setTextColor(color(R.color.murmur_text))
        setHintTextColor(color(R.color.murmur_muted))
        background = rounded(R.color.murmur_background, 8f, stroke = R.color.murmur_border)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        stateListAnimator = null
        minHeight = dp(40)
        minimumHeight = dp(40)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(16), 0, dp(16), 0)
        styleButton(this, primary)
        setOnClickListener { action() }
    }

    private fun styleButton(button: Button, primary: Boolean) {
        button.setTextColor(color(if (primary) R.color.murmur_on_accent else R.color.murmur_text))
        val shape = if (primary) rounded(R.color.murmur_accent, 8f)
        else rounded(android.R.color.transparent, 8f, stroke = R.color.murmur_border)
        button.background = RippleDrawable(ColorStateList.valueOf(color(R.color.murmur_border)), shape, null)
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color(fill))
        cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), color(stroke))
    }

    private fun color(res: Int): Int = getColor(res)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val MICROPHONE_REQUEST = 1
        private const val NOTIFICATION_REQUEST = 2
        private const val HISTORY_EXPORT_REQUEST = 3
        private const val LECTURE_IMPORT_REQUEST = 4
    }
}
