package dev.local.murmur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.local.murmur.DictationStats
import dev.local.murmur.OpenRouterUsage
import dev.local.murmur.OpenRouterUsageClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

@Composable
internal fun StatsScreen(model: AppModel, onBack: () -> Unit) {
    val stats = remember(model.revision) { runCatching { model.history.stats() }.getOrNull() }
    var usage by remember { mutableStateOf<Result<OpenRouterUsage>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf(0) }
    LaunchedEffect(request) {
        loading = true
        usage = withContext(Dispatchers.IO) { runCatching { OpenRouterUsageClient.fetch(model.settings.openRouterUsageKeys()) } }
        loading = false
    }

    ScreenScaffold(title = "Stats", onBack = onBack) {
        item { StatTiles(stats) }
        item { SectionLabel("Weekly activity") }
        item { WeeklyCard(stats) }
        item { SectionLabel("Provider cost") }
        item {
            PaddedCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OpenRouter usage", style = MaterialTheme.typography.titleMedium)
                        Hint("For your configured OpenRouter keys", Modifier.padding(horizontal = 0.dp))
                    }
                    TextButton(onClick = { request++ }, enabled = !loading) { Text(if (loading) "Checking…" else "Refresh") }
                }
                val result = usage
                when {
                    result == null -> Unit
                    result.isFailure -> Text("Could not check OpenRouter usage. Check your connection and key, then retry.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> {
                        val value = result.getOrThrow()
                        if (value.keyCount == 0) Hint("Add an OpenRouter key in Recognition or Cleanup to see provider usage.", Modifier.padding(0.dp))
                        else {
                            Hint("Across ${value.keyCount} configured OpenRouter ${if (value.keyCount == 1) "key" else "keys"}.", Modifier.padding(0.dp))
                            CostRow("This week", formatUsd(value.weekUsd))
                            CostRow("This month", formatUsd(value.monthUsd))
                            CostRow("All time", formatUsd(value.allTimeUsd))
                            if (value.byokMonthUsd > 0.0) Hint(
                                "BYOK model usage this month: ${formatUsd(value.byokMonthUsd)}. Your provider bills this separately.",
                                Modifier.padding(0.dp),
                            )
                        }
                    }
                }
                Hint("These are OpenRouter key totals, including use outside Murmur. Local models and direct provider keys are not counted.",
                    Modifier.padding(0.dp))
            }
        }
    }
}

@Composable
private fun WeeklyCard(stats: DictationStats?) {
    PaddedCard {
        Text("Words each week", style = MaterialTheme.typography.titleMedium)
        Hint(
            when {
                stats == null -> "Statistics are unavailable right now."
                stats.dictations == 0 -> "Your first dictation will start the weekly chart."
                stats.weekOverWeekPercent == null -> "No previous week to compare."
                else -> {
                    val change = stats.weekOverWeekPercent
                    "${if (change > 0) "+" else ""}${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }.format(change)}% vs last full week"
                }
            },
            Modifier.padding(0.dp),
        )
        if (stats == null || stats.dictations == 0) return@PaddedCard
        val highest = stats.weeks.maxOfOrNull { it.words }?.coerceAtLeast(1) ?: 1
        val locale = LocalConfiguration.current.locales[0]
        val dateFormat = remember(locale) { SimpleDateFormat("d MMM", locale) }
        val primary = MaterialTheme.colorScheme.primary
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            stats.weeks.forEachIndexed { index, week ->
                Column(
                    Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription = "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(week.weekStart))}: ${week.words} words"
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (week.words == 0) "" else compactCount(week.words), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.height(88.dp), contentAlignment = Alignment.BottomCenter) {
                        val height = if (week.words == 0) 3.dp else (week.words * 88f / highest).coerceAtLeast(6f).dp
                        Box(
                            Modifier.width(20.dp).height(height).background(
                                when {
                                    week.words == 0 -> MaterialTheme.colorScheme.outlineVariant
                                    index == stats.weeks.lastIndex -> primary
                                    else -> primary.copy(alpha = 0.55f)
                                },
                                RoundedCornerShape(6.dp),
                            ),
                        )
                    }
                    Text(dateFormat.format(Date(week.weekStart)), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
        Text("Recorded pace each week", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth()) {
            stats.weeks.forEach { week ->
                Text(week.recordedWpm?.toString() ?: "—", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Hint(
            if (stats.recordingsWithDuration == 0) "No recorded durations yet, so pace and time cannot be calculated. This week is still in progress."
            else "Pace uses ${stats.recordingsWithDuration} of ${stats.dictations} dictations with recorded duration and includes pauses. This week is still in progress.",
            Modifier.padding(top = 8.dp, start = 0.dp, end = 0.dp),
        )
    }
}

@Composable
private fun CostRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
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
