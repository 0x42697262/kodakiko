package dev.kodakiko.ui.feature.tags

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import dev.kodakiko.contract.TAGS_PER_ENTRY_MAX
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.index.TagCount
import dev.kodakiko.ui.core.tags.ChooseTagsSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class TagsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tags = listOf(
        TagCount("beach", 12),
        TagCount("family", 1),
        TagCount("summer", 7),
        TagCount("travel", 3),
        TagCount("later", 0),
    )

    private fun editor(name: String, state: TagsState, appTheme: AppTheme = AppTheme.LIGHT) {
        compose.show(appTheme) { TagsContent(state = state, onAction = {}, onBack = {}) }
        if (state.dialog == null) compose.onRoot().captureScreenshot("tags/$name") else captureScreenScreenshot("tags/$name")
    }

    private fun chooser(name: String, choices: List<String>, selected: List<String>, single: Boolean = false) {
        compose.show { ChooseTagsSheet(choices = choices, selected = selected, onToggle = {}, onEditTags = {}, onDismiss = {}, single = single) }
        captureScreenScreenshot("choose_tags/$name")
    }

    @Test
    fun tagList() = editor("list", TagsState(tags = tags, dialog = null))

    @Test
    fun tagListDark() = editor("list_dark", TagsState(tags = tags, dialog = null), AppTheme.DARK)

    @Test
    fun noTags() = editor("empty", TagsState(tags = emptyList(), dialog = null))

    @Test
    fun addDialogWithAnInvalidName() = editor("add_invalid", TagsState(tags = tags, dialog = TagsDialog.Add(name = " ", invalid = true)))

    @Test
    fun renameDialog() = editor("rename", TagsState(tags = tags, dialog = TagsDialog.Rename(tag = "summer", name = "summer", invalid = false)))

    @Test
    fun deleteDialog() = editor("delete", TagsState(tags = tags, dialog = TagsDialog.Delete(tag = "beach", count = 12)))

    @Test
    fun chooseTags() = chooser("some_checked", tags.map { it.tag }, listOf("family", "travel"))

    @Test
    fun chooseTagsAtTheLimit() {
        val chosen = (1..TAGS_PER_ENTRY_MAX).map { "tag$it" }
        chooser("at_limit", listOf("beach", "summer") + chosen, chosen)
    }

    @Test
    fun chooseTagsEmpty() = chooser("empty", emptyList(), emptyList())

    @Test
    fun singlePick() = chooser("single_pick", tags.map { it.tag }, listOf("summer"), single = true)
}
