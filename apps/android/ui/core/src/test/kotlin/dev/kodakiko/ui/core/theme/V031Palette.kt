package dev.kodakiko.ui.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

object V031Palette {
    private fun argb(value: Long): Long = value and 0xFFFFFFFFL

    fun pinnedRoles(scheme: ColorScheme): Map<String, Long> = mapOf(
        "primary" to argb(scheme.primary.toArgb().toLong()),
        "onPrimary" to argb(scheme.onPrimary.toArgb().toLong()),
        "primaryContainer" to argb(scheme.primaryContainer.toArgb().toLong()),
        "onPrimaryContainer" to argb(scheme.onPrimaryContainer.toArgb().toLong()),
        "inversePrimary" to argb(scheme.inversePrimary.toArgb().toLong()),
        "secondaryContainer" to argb(scheme.secondaryContainer.toArgb().toLong()),
        "onSecondaryContainer" to argb(scheme.onSecondaryContainer.toArgb().toLong()),
        "background" to argb(scheme.background.toArgb().toLong()),
        "onBackground" to argb(scheme.onBackground.toArgb().toLong()),
        "surface" to argb(scheme.surface.toArgb().toLong()),
        "onSurface" to argb(scheme.onSurface.toArgb().toLong()),
        "surfaceVariant" to argb(scheme.surfaceVariant.toArgb().toLong()),
        "onSurfaceVariant" to argb(scheme.onSurfaceVariant.toArgb().toLong()),
        "outline" to argb(scheme.outline.toArgb().toLong()),
        "error" to argb(scheme.error.toArgb().toLong()),
        "surfaceContainerLow" to argb(scheme.surfaceContainerLow.toArgb().toLong()),
        "surfaceContainer" to argb(scheme.surfaceContainer.toArgb().toLong()),
        "surfaceContainerHigh" to argb(scheme.surfaceContainerHigh.toArgb().toLong()),
    )

    fun allRoles(scheme: ColorScheme): Map<String, Long> = pinnedRoles(scheme) + mapOf(
        "secondary" to argb(scheme.secondary.toArgb().toLong()),
        "onSecondary" to argb(scheme.onSecondary.toArgb().toLong()),
        "tertiary" to argb(scheme.tertiary.toArgb().toLong()),
        "onTertiary" to argb(scheme.onTertiary.toArgb().toLong()),
        "tertiaryContainer" to argb(scheme.tertiaryContainer.toArgb().toLong()),
        "onTertiaryContainer" to argb(scheme.onTertiaryContainer.toArgb().toLong()),
        "surfaceTint" to argb(scheme.surfaceTint.toArgb().toLong()),
        "inverseSurface" to argb(scheme.inverseSurface.toArgb().toLong()),
        "inverseOnSurface" to argb(scheme.inverseOnSurface.toArgb().toLong()),
        "onError" to argb(scheme.onError.toArgb().toLong()),
        "errorContainer" to argb(scheme.errorContainer.toArgb().toLong()),
        "onErrorContainer" to argb(scheme.onErrorContainer.toArgb().toLong()),
        "outlineVariant" to argb(scheme.outlineVariant.toArgb().toLong()),
        "scrim" to argb(scheme.scrim.toArgb().toLong()),
        "surfaceBright" to argb(scheme.surfaceBright.toArgb().toLong()),
        "surfaceDim" to argb(scheme.surfaceDim.toArgb().toLong()),
        "surfaceContainerHighest" to argb(scheme.surfaceContainerHighest.toArgb().toLong()),
        "surfaceContainerLowest" to argb(scheme.surfaceContainerLowest.toArgb().toLong()),
    )

    val LIGHT: Map<String, Long> = mapOf(
        "primary" to 0xFF2F5FD0,
        "onPrimary" to 0xFFFFFFFF,
        "primaryContainer" to 0xFF2F5FD0,
        "onPrimaryContainer" to 0xFFFFFFFF,
        "inversePrimary" to 0xFF7AA2FF,
        "secondaryContainer" to 0xFFDCE3F9,
        "onSecondaryContainer" to 0xFF111114,
        "background" to 0xFFFFFFFF,
        "onBackground" to 0xFF111114,
        "surface" to 0xFFFFFFFF,
        "onSurface" to 0xFF111114,
        "surfaceVariant" to 0xFFF2F2F7,
        "onSurfaceVariant" to 0xFF5C5C66,
        "outline" to 0xFFD1D1D6,
        "error" to 0xFFC62828,
        "surfaceContainerLow" to 0xFFFFFFFF,
        "surfaceContainer" to 0xFFF2F2F7,
        "surfaceContainerHigh" to 0xFFF2F2F7,
    )

    val DARK: Map<String, Long> = mapOf(
        "primary" to 0xFF7AA2FF,
        "onPrimary" to 0xFF0B0B0D,
        "primaryContainer" to 0xFF2A3F7A,
        "onPrimaryContainer" to 0xFFDDE4FF,
        "inversePrimary" to 0xFF2F5FD0,
        "secondaryContainer" to 0xFF2A3354,
        "onSecondaryContainer" to 0xFFF2F2F5,
        "background" to 0xFF0B0B0D,
        "onBackground" to 0xFFF2F2F5,
        "surface" to 0xFF0B0B0D,
        "onSurface" to 0xFFF2F2F5,
        "surfaceVariant" to 0xFF1C1C1F,
        "onSurfaceVariant" to 0xFFC8C8D0,
        "outline" to 0xFF4A4A52,
        "error" to 0xFFFF6B6B,
        "surfaceContainerLow" to 0xFF0B0B0D,
        "surfaceContainer" to 0xFF1C1C1F,
        "surfaceContainerHigh" to 0xFF26262A,
    )

    val PURE_BLACK_OVERRIDES: Map<String, Long> = mapOf(
        "background" to 0xFF000000,
        "onBackground" to 0xFFFFFFFF,
        "surface" to 0xFF000000,
        "onSurface" to 0xFFFFFFFF,
        "surfaceContainerLow" to 0xFF0C0C0C,
        "surfaceVariant" to 0xFF0C0C0C,
        "surfaceContainer" to 0xFF0C0C0C,
        "surfaceContainerHigh" to 0xFF131313,
    )

    val DARK_PURE_BLACK: Map<String, Long> = DARK + PURE_BLACK_OVERRIDES

    val DYNAMIC_PURE_BLACK_OVERRIDES: Map<String, Long> = mapOf(
        "background" to 0xFF000000,
        "onBackground" to 0xFFFFFFFF,
        "surface" to 0xFF000000,
        "onSurface" to 0xFFFFFFFF,
    )

    const val ICON_BACKGROUND: Long = 0xFF1E1D2B
}
