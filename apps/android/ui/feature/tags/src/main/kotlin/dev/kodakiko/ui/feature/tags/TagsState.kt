package dev.kodakiko.ui.feature.tags

import androidx.compose.runtime.Immutable
import dev.kodakiko.index.TagCount

@Immutable
data class TagsState(val tags: List<TagCount>, val dialog: TagsDialog?)

@Immutable
sealed interface TagsDialog {
    data class Add(val name: String, val invalid: Boolean) : TagsDialog

    data class Rename(val tag: String, val name: String, val invalid: Boolean) : TagsDialog

    data class Delete(val tag: String, val count: Int) : TagsDialog
}

sealed interface TagsAction {
    data object Add : TagsAction

    data class NameChanged(val name: String) : TagsAction

    data object Save : TagsAction

    data object Dismiss : TagsAction

    data class Rename(val tag: String) : TagsAction

    data class Delete(val tag: String) : TagsAction

    data object ConfirmDelete : TagsAction

    data class Move(val from: Int, val to: Int) : TagsAction
}
