@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.more

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.js.JsDate
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.format.formatBytes
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlin.math.max
import kotlin.math.roundToLong

private class Metric(val value: String, val label: String)

private class Bar(val label: String, val count: Int)

internal fun videoLengthText(resources: Resources, videoMs: Long): String {
    val totalMinutes = (videoMs / MoreConstants.MS_PER_MINUTE).roundToLong()
    if (totalMinutes == 0L) return resources.getString(R.string.stats_seconds, (videoMs / MoreConstants.MS_PER_SECOND).roundToLong())
    val hours = totalMinutes / MoreConstants.MINUTES_PER_HOUR
    val minutes = totalMinutes % MoreConstants.MINUTES_PER_HOUR
    return if (hours > 0) resources.getString(R.string.stats_hours, hours, minutes) else resources.getString(R.string.stats_hours_minutes_only, minutes)
}

internal fun lastSyncText(lastSuccessAt: Long): String =
    JsDate.toIsoString(lastSuccessAt.toDouble()).substring(MoreConstants.SYNC_TIME_START, MoreConstants.SYNC_TIME_END)

@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoreEventsEffect(viewModel.events)
    StatisticsContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun StatisticsContent(state: StatisticsState, onAction: (StatisticsAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(TestTags.Stats.SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.stats_title),
                navigateUp = { onAction(StatisticsAction.Back) },
                navigateUpTestTag = TestTags.Settings.BACK,
                scrollBehavior = it,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(MoreDimens.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(MoreDimens.CARD_SPACING),
        ) {
            JournalCards(state)
            StorageCards(state)
            Activity(state)
        }
    }
}

@Composable
private fun JournalCards(state: StatisticsState) {
    val stats = state.stats
    val resources = LocalResources.current
    StatCard(
        testTag = TestTags.Stats.OVERVIEW,
        title = stringResource(R.string.stats_overview),
        metrics = listOf(
            Metric(stats.entries.toString(), stringResource(R.string.stats_entries)),
            Metric(stats.mediaItems.toString(), stringResource(R.string.stats_media)),
            Metric(formatBytes(stats.originalBytes), stringResource(R.string.stats_size)),
        ),
    )
    StatCard(
        testTag = TestTags.Stats.MEDIA_CARD,
        title = stringResource(R.string.stats_media),
        metrics = listOf(
            Metric(stats.photos.toString(), stringResource(R.string.stats_photos)),
            Metric(stats.videos.toString(), stringResource(R.string.stats_videos)),
            Metric(videoLengthText(resources, stats.videoMs), stringResource(R.string.stats_video_length)),
        ),
    )
    StatCard(
        testTag = TestTags.Stats.JOURNAL_CARD,
        title = stringResource(R.string.stats_journal),
        metrics = listOf(
            Metric(stats.entriesWithNote.toString(), stringResource(R.string.stats_notes)),
            Metric(stats.tagCount.toString(), stringResource(R.string.stats_tags_count)),
            Metric(stats.favorites.toString(), stringResource(R.string.stats_favorites)),
        ),
    )
}

@Composable
private fun StorageCards(state: StatisticsState) {
    val none = stringResource(R.string.stats_none)
    val usage = state.usage
    StatCard(
        testTag = TestTags.Stats.DEVICE_CARD,
        title = stringResource(R.string.stats_storage),
        metrics = listOf(
            Metric(usage?.let { formatBytes(it.originalsBytes) } ?: none, stringResource(R.string.stats_on_device)),
            Metric(usage?.let { formatBytes(it.thumbnailsBytes) } ?: none, stringResource(R.string.stats_thumbnails)),
            Metric(usage?.let { formatBytes(it.pendingUploadBytes) } ?: none, stringResource(R.string.stats_waiting)),
        ),
    )
    state.user?.let { user ->
        val quota = state.quotaBytes
        StatCard(
            testTag = TestTags.Stats.SERVER_CARD,
            title = stringResource(R.string.stats_server),
            metrics = listOf(
                Metric(formatBytes(user.usedBytes), stringResource(R.string.stats_used)),
                Metric(quota?.let { formatBytes(it) } ?: none, stringResource(R.string.stats_quota)),
                Metric(quota?.let { formatBytes(max(0L, it - user.usedBytes)) } ?: none, stringResource(R.string.stats_free)),
            ),
            ratio = quota?.takeIf { it != 0L }?.let { minOf(MoreConstants.FULL_RATIO, user.usedBytes.toFloat() / it.toFloat()) },
        )
    }
    val sync = state.sync
    StatCard(
        testTag = TestTags.Stats.SYNC_CARD,
        title = stringResource(R.string.stats_sync),
        metrics = listOf(
            Metric(sync.lastSuccessAt?.let(::lastSyncText) ?: none, stringResource(R.string.stats_last_sync)),
            Metric(sync.queued.toString(), stringResource(R.string.stats_queued)),
            Metric(sync.dead.toString(), stringResource(R.string.stats_failed)),
        ),
    )
}

@Composable
private fun Activity(state: StatisticsState) {
    val stats = state.stats
    val none = stringResource(R.string.stats_none)
    SectionHeader(stringResource(R.string.stats_per_month))
    Bars(
        testTag = TestTags.Stats.PER_MONTH,
        bars = stats.perMonth.map { Bar(it.month.substring(MoreConstants.MONTH_LABEL_START, MoreConstants.MONTH_LABEL_END), it.count) },
    )
    SectionHeader(stringResource(R.string.stats_per_year))
    Bars(testTag = TestTags.Stats.PER_YEAR, bars = stats.perYear.map { Bar(it.year, it.count) })
    SectionHeader(stringResource(R.string.stats_top_tags))
    if (stats.topTags.isEmpty()) Text(text = none, color = MaterialTheme.colorScheme.onSurfaceVariant)
    stats.topTags.forEach { LabeledValue(label = MoreConstants.TAG_PREFIX + it.tag, value = it.count.toString()) }
    LabeledValue(
        label = stringResource(R.string.stats_first_last),
        value = stats.firstDay?.let { it + MoreConstants.DAY_RANGE_SEPARATOR + (stats.lastDay ?: none) } ?: none,
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = MoreDimens.SECTION_TOP),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MoreDimens.CARD_SPACING)) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatCard(testTag: String, title: String, metrics: List<Metric>, ratio: Float? = null) {
    Column(modifier = Modifier.fillMaxWidth().testTag(testTag), verticalArrangement = Arrangement.spacedBy(MoreDimens.METRIC_SPACING)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(MoreDimens.CARD_PADDING), verticalArrangement = Arrangement.spacedBy(MoreDimens.CARD_SPACING)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    metrics.forEach { metric ->
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = metric.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text(
                                text = metric.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                ratio?.let { value ->
                    LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().height(MoreDimens.QUOTA_BAR_HEIGHT))
                }
            }
        }
    }
}

@Composable
private fun Bars(testTag: String, bars: List<Bar>) {
    val highest = max(MoreConstants.BAR_MIN_MAX, bars.maxOfOrNull { it.count } ?: 0)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MoreDimens.BAR_HEIGHT).testTag(testTag),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(MoreDimens.BAR_SPACING),
    ) {
        bars.forEach { bar ->
            val description = bar.label + MoreConstants.BAR_LABEL_SEPARATOR + bar.count
            Column(
                modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MoreDimens.METRIC_SPACING),
            ) {
                Text(
                    text = if (bar.count == 0) MoreConstants.NO_COUNT else bar.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MoreDimens.BAR_HEIGHT * (bar.count.toFloat() / highest))
                        .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall),
                )
                Text(text = bar.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
