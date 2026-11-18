@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.core.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.Screenshots
import dev.kodakiko.ui.core.TestIcons
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.captureScreenScreenshot
import dev.kodakiko.ui.core.captureScreenshot
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.AppBarActions
import dev.kodakiko.ui.core.components.Badge
import dev.kodakiko.ui.core.components.BadgeGroup
import dev.kodakiko.ui.core.components.CheckboxItem
import dev.kodakiko.ui.core.components.HeadingItem
import dev.kodakiko.ui.core.components.ListGroupHeader
import dev.kodakiko.ui.core.components.Pill
import dev.kodakiko.ui.core.components.SearchToolbar
import dev.kodakiko.ui.core.components.SliderItem
import dev.kodakiko.ui.core.components.SortItem
import dev.kodakiko.ui.core.components.TabbedDialog
import dev.kodakiko.ui.core.components.TabbedDialogTab
import dev.kodakiko.ui.core.components.TriState
import dev.kodakiko.ui.core.components.TriStateItem
import dev.kodakiko.ui.core.components.VerticalFastScroller
import dev.kodakiko.ui.core.components.material.AlertDialog
import dev.kodakiko.ui.core.components.material.DialogButton
import dev.kodakiko.ui.core.components.material.KodakikoSnackbarHost
import dev.kodakiko.ui.core.components.material.PullRefresh
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.SnackbarAction
import dev.kodakiko.ui.core.components.material.SnackbarMessage
import dev.kodakiko.ui.core.components.material.showMessage
import dev.kodakiko.ui.core.screens.EmptyScreen
import dev.kodakiko.ui.core.screens.EmptyScreenAction
import dev.kodakiko.ui.core.screens.InfoScreen
import dev.kodakiko.ui.core.screens.LoadingScreen
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class ComponentScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
        compose.setContent {
            KodakikoTheme(appTheme = appTheme) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun SampleAppBar() {
        AppBar(
            title = string(R.string.gallery_title),
            subtitle = string(R.string.tabs_gallery),
            navigateUp = {},
            navigateUpTestTag = TestTags.Trash.BACK,
            actions = {
                AppBarActions(
                    actions = listOf(
                        AppBar.Action(title = string(R.string.gallery_search), icon = TestIcons.Square, testTag = TestTags.Gallery.SEARCH, onClick = {}),
                        AppBar.OverflowAction(title = string(R.string.gallery_random), testTag = TestTags.Gallery.RANDOM, onClick = {}),
                        AppBar.OverflowAction(title = string(R.string.lock_lock_now), testTag = TestTags.Lock.LOCK_NOW, onClick = {}),
                        AppBar.OverflowAction(title = string(R.string.gallery_trash), testTag = TestTags.Gallery.TRASH, onClick = {}),
                    ),
                    overflowTestTag = TestTags.Gallery.MORE,
                )
            },
            modifier = Modifier.testTag(NODE),
        )
    }

    @Test
    fun appBarLight() {
        show { SampleAppBar() }
        compose.onNodeWithTag(NODE).captureScreenshot("components/app_bar_light")
    }

    @Test
    fun appBarDark() {
        show(AppTheme.DARK) { SampleAppBar() }
        compose.onNodeWithTag(NODE).captureScreenshot("components/app_bar_dark")
    }

    @Test
    fun overflowMenuOpen() {
        show { SampleAppBar() }
        compose.onNodeWithTag(TestTags.Gallery.MORE).performClick()
        compose.waitForIdle()
        captureScreenScreenshot("components/overflow_menu_open")
    }

    @Test
    fun searchToolbarClosed() {
        show {
            SearchToolbar(
                searchQuery = null,
                onChangeSearchQuery = {},
                titleContent = { Text(string(R.string.gallery_title)) },
                placeholderText = string(R.string.gallery_search_field),
                searchTestTag = TestTags.Gallery.SEARCH,
                searchFieldTestTag = TestTags.Gallery.SEARCH_FIELD,
                modifier = Modifier.testTag(NODE),
            )
        }
        compose.onNodeWithTag(NODE).captureScreenshot("components/search_toolbar_closed")
    }

    @Test
    fun searchToolbarWithQuery() {
        show {
            SearchToolbar(
                searchQuery = "beach",
                onChangeSearchQuery = {},
                placeholderText = string(R.string.gallery_search_field),
                searchTestTag = TestTags.Gallery.SEARCH,
                searchFieldTestTag = TestTags.Gallery.SEARCH_FIELD,
                modifier = Modifier.testTag(NODE),
            )
        }
        compose.onNodeWithTag(NODE).captureScreenshot("components/search_toolbar_query")
    }

    @Test
    fun searchToolbarEmptyQueryShowsPlaceholder() {
        show {
            SearchToolbar(
                searchQuery = "",
                onChangeSearchQuery = {},
                placeholderText = string(R.string.gallery_search_field),
                searchTestTag = TestTags.Gallery.SEARCH,
                searchFieldTestTag = TestTags.Gallery.SEARCH_FIELD,
                modifier = Modifier.testTag(NODE),
            )
        }
        compose.onNodeWithTag(NODE).captureScreenshot("components/search_toolbar_placeholder")
    }

    private fun snackbarScreen(appTheme: AppTheme, name: String) {
        val host = SnackbarHostState()
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            KodakikoTheme(appTheme = appTheme) {
                Scaffold(
                    topBar = { AppBar(title = string(R.string.gallery_title)) },
                    snackbarHost = { KodakikoSnackbarHost(host) },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            scope.launch {
                host.showMessage(
                    SnackbarMessage(
                        text = string(R.string.advanced_reset_done),
                        testTag = TestTags.Advanced.MESSAGE,
                        action = SnackbarAction(label = string(R.string.sync_retry), testTag = TestTags.Sync.RETRY, onClick = {}),
                    ),
                )
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.onRoot().captureScreenshot(name)
    }

    @Test
    fun scaffoldWithSnackbarLight() = snackbarScreen(AppTheme.LIGHT, "components/scaffold_snackbar_light")

    @Test
    fun scaffoldWithSnackbarDark() = snackbarScreen(AppTheme.DARK, "components/scaffold_snackbar_dark")

    private fun alertDialog(appTheme: AppTheme, name: String) {
        show(appTheme) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = DialogButton(text = string(R.string.settings_sign_out), testTag = TestTags.Settings.SIGN_OUT_CONFIRM, onClick = {}),
                dismissButton = DialogButton(text = string(R.string.settings_cancel), testTag = TestTags.Settings.CANCEL, onClick = {}),
                title = string(R.string.settings_sign_out),
                text = string(R.string.settings_sign_out_confirm),
                destructive = true,
            )
        }
        captureScreenScreenshot(name)
    }

    @Test
    fun alertDialogLight() = alertDialog(AppTheme.LIGHT, "components/alert_dialog_light")

    @Test
    fun alertDialogDark() = alertDialog(AppTheme.DARK, "components/alert_dialog_dark")

    @Test
    fun emptyScreen() {
        show {
            EmptyScreen(
                message = string(R.string.gallery_empty),
                actions = listOf(
                    EmptyScreenAction(title = string(R.string.gallery_clear_filters), icon = TestIcons.Square, testTag = TestTags.Gallery.CLEAR_FILTERS, onClick = {}),
                ),
            )
        }
        compose.onRoot().captureScreenshot("screens/empty_screen")
    }

    @Test
    fun loadingScreen() {
        show { LoadingScreen(indicatorTestTag = TestTags.App.LOADING) }
        compose.onRoot().captureScreenshot("screens/loading_screen")
    }

    @Test
    fun infoScreen() {
        show {
            InfoScreen(
                icon = TestIcons.Square,
                headingText = string(R.string.welcome_title),
                subtitleText = string(R.string.welcome_subtitle),
                acceptText = string(R.string.welcome_continue),
                onAcceptClick = {},
                rejectText = string(R.string.welcome_local),
                onRejectClick = {},
            ) {
                Text(string(R.string.welcome_server_label))
            }
        }
        compose.onRoot().captureScreenshot("screens/info_screen")
    }

    @Composable
    private fun SheetContent() {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            HeadingItem(text = string(R.string.sheet_filter))
            TriStateItem(label = string(R.string.sheet_has_video), state = TriState.ENABLED_IS, onClick = {})
            TriStateItem(label = string(R.string.sheet_has_note), state = TriState.ENABLED_NOT, onClick = {})
            TriStateItem(label = string(R.string.sheet_multiple), state = TriState.DISABLED, onClick = {})
        }
    }

    private fun adaptiveSheet(appTheme: AppTheme, name: String) {
        show(appTheme) {
            Text(string(R.string.gallery_title), modifier = Modifier.padding(16.dp))
            AdaptiveSheet(onDismissRequest = {}, modifier = Modifier.testTag(TestTags.Filter.SHEET)) { SheetContent() }
        }
        captureScreenScreenshot(name)
    }

    @Test
    fun adaptiveSheetLight() = adaptiveSheet(AppTheme.LIGHT, "components/adaptive_sheet_light")

    @Test
    fun adaptiveSheetDark() = adaptiveSheet(AppTheme.DARK, "components/adaptive_sheet_dark")

    @Test
    fun tabbedDialog() {
        show {
            TabbedDialog(
                onDismissRequest = {},
                tabs = listOf(
                    TabbedDialogTab(title = string(R.string.sheet_filter), testTag = "${TestTags.Sheet.TAB}.filter"),
                    TabbedDialogTab(title = string(R.string.sheet_sort), testTag = "${TestTags.Sheet.TAB}.sort"),
                    TabbedDialogTab(title = string(R.string.sheet_display), testTag = "${TestTags.Sheet.TAB}.display"),
                    TabbedDialogTab(title = string(R.string.sheet_group), testTag = "${TestTags.Sheet.TAB}.group"),
                ),
                modifier = Modifier.testTag(TestTags.Filter.SHEET),
                tabOverflowMenuContent = { close ->
                    DropdownMenuItem(text = { Text(string(R.string.filter_reset)) }, onClick = close)
                },
            ) { page ->
                if (page == 0) SheetContent() else Box(Modifier.fillMaxWidth().height(PAGE_HEIGHT.dp))
            }
        }
        captureScreenScreenshot("components/tabbed_dialog")
    }

    @Test
    fun badgesPillsAndHeader() {
        show {
            Column(Modifier.testTag(NODE).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListGroupHeader(text = string(R.string.sheet_tags_and_dates))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BadgeGroup {
                        Badge(text = "12")
                        Badge(imageVector = TestIcons.Square)
                    }
                    Badge(text = "3", color = MaterialTheme.colorScheme.primary, textColor = MaterialTheme.colorScheme.onPrimary)
                    Pill(text = "42")
                    Pill(text = string(R.string.tabs_shared))
                }
            }
        }
        compose.onNodeWithTag(NODE).captureScreenshot("components/badges_pills_header")
    }

    @Test
    fun settingsItems() {
        show {
            Column(Modifier.testTag(NODE)) {
                HeadingItem(text = string(R.string.sheet_sort))
                SortItem(label = string(R.string.sort_taken_at), sortDescending = true, onClick = {})
                SortItem(label = string(R.string.sort_note_length), sortDescending = false, onClick = {})
                SortItem(label = string(R.string.sort_media_count), sortDescending = null, onClick = {})
                HeadingItem(text = string(R.string.sheet_filter))
                TriStateItem(label = string(R.string.sheet_has_video), state = TriState.ENABLED_IS, onClick = {})
                TriStateItem(label = string(R.string.sheet_has_note), state = TriState.ENABLED_NOT, onClick = {})
                TriStateItem(label = string(R.string.sheet_multiple), state = TriState.DISABLED, onClick = {})
                TriStateItem(label = string(R.string.sheet_pending), state = TriState.ENABLED_IS, onClick = {}, enabled = false)
                CheckboxItem(label = string(R.string.sheet_favorite), checked = true, onClick = {})
                CheckboxItem(label = string(R.string.sheet_shared), checked = false, onClick = {})
                SliderItem(value = 4, valueRange = 0..10, label = string(R.string.prefs_columns_portrait), onChange = {})
            }
        }
        compose.onNodeWithTag(NODE).captureScreenshot("components/settings_items")
    }

    @Test
    fun pullRefreshRefreshing() {
        show {
            PullRefresh(refreshing = true, enabled = true, onRefresh = {}, modifier = Modifier.testTag(TestTags.PULL_TO_SYNC)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ROWS) { index -> Text("row $index", modifier = Modifier.padding(16.dp)) }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.onRoot().captureScreenshot("components/pull_refresh_refreshing")
    }

    @Test
    fun fastScrollerVisible() {
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            KodakikoTheme {
                scope = rememberCoroutineScope()
                listState = rememberLazyListState()
                Surface(color = MaterialTheme.colorScheme.background) {
                    VerticalFastScroller(listState = listState) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(ROWS) { index -> Text("row $index", modifier = Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            scope.launch {
                listState.scrollToItem(ROWS / 2)
                listState.animateScrollBy(REVEAL_SCROLL)
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.onRoot().captureScreenshot("components/fast_scroller")
    }

    private companion object {
        const val NODE = "test.screenshot.node"
        const val ROWS = 200
        const val PAGE_HEIGHT = 200f
        const val SETTLE_MILLIS = 1_000L
        const val REVEAL_SCROLL = 150f
    }
}
