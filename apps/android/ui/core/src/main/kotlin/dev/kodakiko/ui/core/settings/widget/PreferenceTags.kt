package dev.kodakiko.ui.core.settings.widget

import dev.kodakiko.data.db.WireValue
import dev.kodakiko.ui.core.settings.SettingsConstants

internal fun dialogTag(testTag: String): String = testTag + SettingsConstants.OPTION_TAG_SEPARATOR + SettingsConstants.DIALOG_TAG_SUFFIX

internal fun switchTag(testTag: String): String = testTag + SettingsConstants.OPTION_TAG_SEPARATOR + SettingsConstants.SWITCH_TAG_SUFFIX

internal fun optionTag(testTag: String, key: Any?): String {
    val suffix = (key as? WireValue)?.wire ?: key.toString()
    return testTag + SettingsConstants.OPTION_TAG_SEPARATOR + suffix
}
