package dev.kodakiko.ui.feature.tags

import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.index.IndexService
import kotlinx.coroutines.flow.StateFlow

interface TagsSettings {
    val settings: StateFlow<DeviceSettings>

    fun update(change: (DeviceSettings) -> DeviceSettings)
}

class TagsDeps(
    val index: IndexService,
    val entries: EntriesService,
    val settings: TagsSettings,
)
