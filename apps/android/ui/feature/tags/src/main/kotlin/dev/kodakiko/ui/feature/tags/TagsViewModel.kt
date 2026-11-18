package dev.kodakiko.ui.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.domain.normalizeTag
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import dev.kodakiko.index.TagCount
import dev.kodakiko.index.orderTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TagsViewModel(private val deps: TagsDeps) : ViewModel() {
    private val dialog = MutableStateFlow<TagsDialog?>(null)

    val state: StateFlow<TagsState> = combine(deps.index.version, deps.settings.settings, dialog) { _, settings, current -> TagsState(tags(settings), current) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(TagsConstants.STATE_SUBSCRIPTION_MS), TagsState(tags(deps.settings.settings.value), null))

    fun onAction(action: TagsAction) {
        when (action) {
            TagsAction.Add -> dialog.value = TagsDialog.Add(name = "", invalid = false)
            is TagsAction.Rename -> dialog.value = TagsDialog.Rename(tag = action.tag, name = action.tag, invalid = false)
            is TagsAction.Delete -> dialog.value = TagsDialog.Delete(tag = action.tag, count = currentTags().firstOrNull { it.tag == action.tag }?.count ?: 0)
            is TagsAction.NameChanged -> nameChanged(action.name)
            TagsAction.Save -> save()
            TagsAction.ConfirmDelete -> confirmDelete()
            TagsAction.Dismiss -> dialog.value = null
            is TagsAction.Move -> move(action.from, action.to)
        }
    }

    private fun tags(settings: DeviceSettings): List<TagCount> = orderTags(deps.index.index()?.allTags().orEmpty(), settings.tagOrder)

    private fun currentTags(): List<TagCount> = tags(deps.settings.settings.value)

    private fun fullOrder(): List<String> = currentTags().map { it.tag }

    private fun nameChanged(name: String) {
        dialog.update { current ->
            when (current) {
                is TagsDialog.Add -> current.copy(name = name)
                is TagsDialog.Rename -> current.copy(name = name)
                else -> current
            }
        }
    }

    private fun save() {
        when (val current = dialog.value) {
            is TagsDialog.Add -> add(current)
            is TagsDialog.Rename -> rename(current)
            else -> Unit
        }
    }

    private fun add(current: TagsDialog.Add) {
        val normalized = normalizeTag(current.name)
        if (normalized == null) {
            dialog.value = current.copy(invalid = true)
            return
        }
        val order = fullOrder()
        if (normalized !in order) writeOrder(order + normalized)
        dialog.value = null
    }

    private fun rename(current: TagsDialog.Rename) {
        val normalized = normalizeTag(current.name)
        if (normalized == null) {
            dialog.value = current.copy(invalid = true)
            return
        }
        val from = current.tag
        val order = fullOrder()
        val entryIds = entriesWith(from)
        dialog.value = null
        viewModelScope.launch {
            if (attempt { deps.entries.renameTag(entryIds, from, normalized) }) writeOrder(order.map { if (it == from) normalized else it })
        }
    }

    private fun confirmDelete() {
        val current = dialog.value as? TagsDialog.Delete ?: return
        val tag = current.tag
        val order = fullOrder()
        val entryIds = entriesWith(tag)
        dialog.value = null
        viewModelScope.launch {
            if (attempt { deps.entries.deleteTag(entryIds, tag) }) writeOrder(order.filter { it != tag })
        }
    }

    private fun move(from: Int, to: Int) {
        val order = fullOrder()
        if (from !in order.indices || to !in order.indices || from == to) return
        writeOrder(moveTag(order, order[from], to - from))
    }

    private fun writeOrder(order: List<String>) {
        deps.settings.update { it.copy(tagOrder = order) }
    }

    private fun entriesWith(tag: String): List<String> =
        deps.index.index()?.query(GalleryFilter(GalleryScope.Home, GalleryView.JOURNAL, listOf(tag), null, null, ""))?.flatMap { it.entryIds }.orEmpty()

    private suspend fun attempt(work: suspend () -> Unit): Boolean = try {
        work()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
