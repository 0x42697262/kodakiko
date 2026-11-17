package dev.kodakiko.ui.feature.links

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import dev.kodakiko.js.JsDate
import dev.kodakiko.js.jsRound
import dev.kodakiko.service.links.CreateLinkProgress
import dev.kodakiko.service.links.CreateLinkResult
import dev.kodakiko.service.links.CreateLinkStep
import dev.kodakiko.service.links.OwnerLink
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.format.formatBytes
import kotlin.math.max

@Immutable
sealed interface LinkExpiryLabel {
    data object OneViewUnopened : LinkExpiryLabel

    data object Opened : LinkExpiryLabel

    data object Never : LinkExpiryLabel

    data class InDays(val days: Int) : LinkExpiryLabel

    data class InHours(val hours: Int) : LinkExpiryLabel
}

fun linkExpiryLabel(link: OwnerLink, nowMs: Long): LinkExpiryLabel {
    val maxViews = link.maxViews
    if (maxViews != null) return if (link.viewCount >= maxViews) LinkExpiryLabel.Opened else LinkExpiryLabel.OneViewUnopened
    val expiresAt = link.expiresAt ?: return LinkExpiryLabel.Never
    val remaining = JsDate.parse(expiresAt) - nowMs
    if (remaining.isNaN()) return LinkExpiryLabel.Never
    val days = jsRound(remaining / LinksConstants.DAY_MS)
    if (days >= LinksConstants.MIN_DAYS) return LinkExpiryLabel.InDays(days.toInt())
    return LinkExpiryLabel.InHours(max(LinksConstants.MIN_HOURS, jsRound(remaining / LinksConstants.HOUR_MS).toInt()))
}

fun linkExpiryText(resources: Resources, label: LinkExpiryLabel): String = when (label) {
    LinkExpiryLabel.OneViewUnopened -> resources.getString(R.string.links_one_view_unopened)
    LinkExpiryLabel.Opened -> resources.getString(R.string.links_opened)
    LinkExpiryLabel.Never -> resources.getString(R.string.links_never_expires)
    is LinkExpiryLabel.InDays -> resources.getString(R.string.links_expires_in, resources.getQuantityString(R.plurals.links_in_days, label.days, label.days))
    is LinkExpiryLabel.InHours -> resources.getString(R.string.links_expires_in, resources.getString(R.string.links_in_hours, label.hours))
}

fun linkRowDetail(resources: Resources, link: OwnerLink, nowMs: Long): String {
    val views = link.viewCount.toInt()
    return resources.getString(
        R.string.links_row_detail,
        linkExpiryText(resources, linkExpiryLabel(link, nowMs)),
        resources.getQuantityString(R.plurals.links_views, views, link.viewCount),
    )
}

fun linkSkippedLines(resources: Resources, result: CreateLinkResult): List<String> = buildList {
    if (result.skippedEntries > 0) add(resources.getQuantityString(R.plurals.links_skipped_entries, result.skippedEntries, result.skippedEntries))
    if (result.skippedUndecodable > 0) {
        add(resources.getQuantityString(R.plurals.links_skipped_undecodable, result.skippedUndecodable, result.skippedUndecodable))
    }
    if (result.skippedUnsupportedVideo > 0) {
        add(resources.getQuantityString(R.plurals.links_skipped_video, result.skippedUnsupportedVideo, result.skippedUnsupportedVideo))
    }
}

fun linkProgressText(resources: Resources, progress: CreateLinkProgress): String = when (progress.step) {
    CreateLinkStep.PREPARING -> resources.getString(R.string.links_preparing, progress.done + LinksConstants.FIRST_STEP_NUMBER, progress.total)
    CreateLinkStep.UPLOADING -> resources.getString(R.string.links_uploading, formatBytes(progress.bytesDone), formatBytes(progress.bytesTotal))
    CreateLinkStep.REGISTERING -> resources.getString(R.string.links_registering)
}
