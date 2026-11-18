package dev.kodakiko.ui.core.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestIcons
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.pressBack
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferenceWidgetsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordingHaptics : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    private fun tagged(tag: String) = compose.onNodeWithTag(tag)

    private fun switchOf(tag: String) = compose.onNodeWithTag("$tag.switch", useUnmergedTree = true)

    private fun within(tag: String) = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))

    private fun count(matcher: SemanticsMatcher): Int = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun toggleable(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    private fun show(items: () -> List<Preference>) {
        compose.setContent {
            KodakikoTheme {
                PreferenceScreen(items = items())
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aSwitchRowShowsItsTextsReportsItsStateAndTogglesFromTheRowAndTheSwitch() {
        var value by mutableStateOf(true)
        val changes = mutableListOf<Boolean>()
        val haptics = RecordingHaptics()
        compose.setContent {
            KodakikoTheme {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    PreferenceScreen(
                        items = listOf(
                            Preference.PreferenceItem.SwitchPreference(
                                value = value,
                                title = string(R.string.prefs_secure_screen),
                                subtitle = string(R.string.prefs_secure_screen_summary),
                                testTag = TestTags.Settings.SECURE_SCREEN,
                                onValueChanged = {
                                    changes += it
                                    value = it
                                },
                            ),
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
        tagged(TestTags.Settings.SECURE_SCREEN)
            .assertIsDisplayed()
            .assert(hasText(string(R.string.prefs_secure_screen)))
            .assert(hasText(string(R.string.prefs_secure_screen_summary)))
            .assert(role(Role.Switch))
            .assertIsOn()
        switchOf(TestTags.Settings.SECURE_SCREEN).assertIsDisplayed()
        tagged(TestTags.Settings.SECURE_SCREEN).performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), changes)
        tagged(TestTags.Settings.SECURE_SCREEN).assertIsOff()
        switchOf(TestTags.Settings.SECURE_SCREEN).performClick()
        compose.waitForIdle()
        assertEquals(listOf(false, true), changes)
        tagged(TestTags.Settings.SECURE_SCREEN).assertIsOn()
        assertEquals(2, haptics.performed.size)
    }

    @Test
    fun aDisabledSwitchIgnoresTaps() {
        val changes = mutableListOf<Boolean>()
        show {
            listOf(
                Preference.PreferenceItem.SwitchPreference(
                    value = false,
                    title = string(R.string.settings_biometric),
                    testTag = TestTags.Settings.BIOMETRIC,
                    enabled = false,
                    onValueChanged = { changes += it },
                ),
            )
        }
        tagged(TestTags.Settings.BIOMETRIC).assertIsNotEnabled().assertIsOff()
        tagged(TestTags.Settings.BIOMETRIC).performClick()
        switchOf(TestTags.Settings.BIOMETRIC).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test
    fun aListRowShowsTheChosenLabelAndPicksFromADialogOfTaggedOptions() {
        var value by mutableStateOf(StartScreen.GALLERY)
        val changes = mutableListOf<StartScreen>()
        show {
            listOf(
                Preference.PreferenceItem.ListPreference(
                    value = value,
                    entries = mapOf(
                        StartScreen.GALLERY to string(R.string.tabs_gallery),
                        StartScreen.CALENDAR to string(R.string.tabs_calendar),
                    ),
                    title = string(R.string.prefs_start_screen),
                    testTag = TestTags.Settings.START_SCREEN,
                    onValueChanged = {
                        changes += it
                        value = it
                    },
                ),
            )
        }
        val dialog = "${TestTags.Settings.START_SCREEN}.dialog"
        tagged(TestTags.Settings.START_SCREEN)
            .assert(hasText(string(R.string.prefs_start_screen)))
            .assert(hasText(string(R.string.tabs_gallery)))
        assertEquals(0, count(hasTestTag(dialog)))
        tagged(TestTags.Settings.START_SCREEN).performClick()
        compose.waitForIdle()
        tagged(dialog).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.prefs_start_screen)) and within(dialog)).assertIsDisplayed()
        tagged("${TestTags.Settings.START_SCREEN}.gallery").assertIsSelected()
        tagged("${TestTags.Settings.START_SCREEN}.calendar").assertIsNotSelected().assert(hasText(string(R.string.tabs_calendar))).performClick()
        compose.waitForIdle()
        assertEquals(listOf(StartScreen.CALENDAR), changes)
        assertEquals(0, count(hasTestTag(dialog)))
        tagged(TestTags.Settings.START_SCREEN).assert(hasText(string(R.string.tabs_calendar)))
    }

    @Test
    fun backClosesAListDialogWithoutAChange() {
        val changes = mutableListOf<StartScreen>()
        show {
            listOf(
                Preference.PreferenceItem.ListPreference(
                    value = StartScreen.GALLERY,
                    entries = mapOf(
                        StartScreen.GALLERY to string(R.string.tabs_gallery),
                        StartScreen.CALENDAR to string(R.string.tabs_calendar),
                    ),
                    title = string(R.string.prefs_start_screen),
                    testTag = TestTags.Settings.START_SCREEN,
                    onValueChanged = { changes += it },
                ),
            )
        }
        tagged(TestTags.Settings.START_SCREEN).performClick()
        compose.waitForIdle()
        tagged("${TestTags.Settings.START_SCREEN}.dialog").assertIsDisplayed()
        compose.pressBack()
        assertEquals(0, count(hasTestTag("${TestTags.Settings.START_SCREEN}.dialog")))
        assertEquals(emptyList<StartScreen>(), changes)
    }

    @Test
    fun listOptionTagsUseTheValueTextAndAnExplicitSubtitleWins() {
        val changes = mutableListOf<Int>()
        show {
            listOf(
                Preference.PreferenceItem.ListPreference(
                    value = 5,
                    entries = mapOf(1 to "1", 5 to "5", 15 to "15"),
                    title = string(R.string.settings_auto_lock),
                    subtitle = string(R.string.prefs_security_summary),
                    testTag = TestTags.Settings.AUTO_LOCK,
                    onValueChanged = { changes += it },
                ),
            )
        }
        tagged(TestTags.Settings.AUTO_LOCK).assert(hasText(string(R.string.prefs_security_summary)))
        tagged(TestTags.Settings.AUTO_LOCK).performClick()
        compose.waitForIdle()
        tagged("${TestTags.Settings.AUTO_LOCK}.5").assertIsSelected()
        tagged("${TestTags.Settings.AUTO_LOCK}.15").performClick()
        compose.waitForIdle()
        assertEquals(listOf(15), changes)
    }

    @Test
    fun aMultiSelectRowListsTheChosenLabelsAndConfirmsOrCancelsItsDialog() {
        var values by mutableStateOf(setOf("video", "note"))
        val changes = mutableListOf<Set<String>>()
        val entries = mapOf(
            "video" to string(R.string.prefs_badge_video),
            "photoCount" to string(R.string.prefs_badge_photo_count),
            "note" to string(R.string.prefs_badge_note),
        )
        show {
            listOf(
                Preference.PreferenceItem.MultiSelectListPreference(
                    values = values,
                    entries = entries,
                    title = string(R.string.prefs_badges),
                    testTag = TestTags.Settings.BADGE,
                    onValuesChanged = {
                        changes += it
                        values = it
                    },
                ),
            )
        }
        val dialog = "${TestTags.Settings.BADGE}.dialog"
        tagged(TestTags.Settings.BADGE)
            .assert(hasText(string(R.string.prefs_badges)))
            .assert(hasText("${string(R.string.prefs_badge_video)}, ${string(R.string.prefs_badge_note)}"))
        tagged(TestTags.Settings.BADGE).performClick()
        compose.waitForIdle()
        tagged(dialog).assertIsDisplayed()
        tagged("${TestTags.Settings.BADGE}.video").assert(toggleable(ToggleableState.On))
        tagged("${TestTags.Settings.BADGE}.photoCount").assert(toggleable(ToggleableState.Off)).performClick()
        tagged("${TestTags.Settings.BADGE}.video").performClick()
        compose.waitForIdle()
        tagged("${TestTags.Settings.BADGE}.photoCount").assert(toggleable(ToggleableState.On))
        tagged("${TestTags.Settings.BADGE}.video").assert(toggleable(ToggleableState.Off))
        assertEquals(emptyList<Set<String>>(), changes)
        compose.onNode(hasText(string(R.string.tags_done)) and within(dialog)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(setOf("photoCount", "note")), changes)
        assertEquals(0, count(hasTestTag(dialog)))
        tagged(TestTags.Settings.BADGE)
            .assert(hasText("${string(R.string.prefs_badge_photo_count)}, ${string(R.string.prefs_badge_note)}"))
        tagged(TestTags.Settings.BADGE).performClick()
        compose.waitForIdle()
        tagged("${TestTags.Settings.BADGE}.video").performClick()
        compose.onNode(hasText(string(R.string.settings_cancel)) and within(dialog)).performClick()
        compose.waitForIdle()
        assertEquals(1, changes.size)
        assertEquals(0, count(hasTestTag(dialog)))
    }

    @Test
    fun aSliderRowShowsItsValueTextAndReportsWholeSteps() {
        var columns by mutableIntStateOf(3)
        val changes = mutableListOf<Int>()
        show {
            listOf(
                Preference.PreferenceItem.SliderPreference(
                    value = columns,
                    valueRange = 0..10,
                    title = string(R.string.prefs_columns_portrait),
                    valueText = { if (it == 0) string(R.string.prefs_columns_auto) else it.toString() },
                    testTag = TestTags.Settings.COLUMNS_PORTRAIT,
                    onValueChanged = {
                        changes += it
                        columns = it
                    },
                ),
            )
        }
        val area = within(TestTags.Settings.COLUMNS_PORTRAIT)
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and area
        compose.onNode(hasText(string(R.string.prefs_columns_portrait)) and area).assertIsDisplayed()
        compose.onNode(hasText("3") and area).assertIsDisplayed()
        compose.onNode(slider).assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(3f, 0f..10f, 9)))
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(7.4f) }
        compose.waitForIdle()
        assertEquals(7, changes.last())
        compose.onNode(hasText("7") and area).assertIsDisplayed()
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitForIdle()
        assertEquals(0, changes.last())
        compose.onNode(hasText(string(R.string.prefs_columns_auto)) and area).assertIsDisplayed()
    }

    @Test
    fun aTextRowShowsTitleAndSubtitleAndRunsItsAction() {
        var clicks = 0
        show {
            listOf(
                Preference.PreferenceItem.TextPreference(
                    title = string(R.string.prefs_appearance),
                    subtitle = string(R.string.prefs_appearance_summary),
                    icon = TestIcons.Square,
                    testTag = TestTags.Settings.ROW_APPEARANCE,
                    onClick = { clicks++ },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = string(R.string.settings_app_version),
                    subtitle = "0.4.0",
                    testTag = TestTags.Settings.APP_VERSION,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = string(R.string.settings_sign_out),
                    testTag = TestTags.Settings.ROW_SIGN_OUT,
                    danger = true,
                    enabled = false,
                    onClick = { clicks += 10 },
                ),
            )
        }
        tagged(TestTags.Settings.ROW_APPEARANCE)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assert(hasText(string(R.string.prefs_appearance)))
            .assert(hasText(string(R.string.prefs_appearance_summary)))
            .performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
        tagged(TestTags.Settings.APP_VERSION).assertHasNoClickAction()
        tagged(TestTags.Settings.ROW_SIGN_OUT).assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun anInfoItemShowsItsTextAndIsNotClickable() {
        show {
            listOf(
                Preference.PreferenceItem.InfoPreference(
                    title = string(R.string.prefs_full_screen_summary),
                    testTag = TestTags.Settings.LOCAL_ONLY,
                ),
            )
        }
        tagged(TestTags.Settings.LOCAL_ONLY)
            .assertIsDisplayed()
            .assert(hasText(string(R.string.prefs_full_screen_summary)))
            .assertHasNoClickAction()
    }

    @Test
    fun aGroupShowsAHeadingAboveItsVisibleItemsAndHiddenPartsAreNotRendered() {
        show {
            listOf(
                Preference.PreferenceGroup(
                    title = string(R.string.prefs_appearance),
                    preferenceItems = listOf(
                        Preference.PreferenceItem.SwitchPreference(
                            value = true,
                            title = string(R.string.prefs_full_screen),
                            testTag = TestTags.Settings.FULL_SCREEN,
                            onValueChanged = {},
                        ),
                        Preference.PreferenceItem.SwitchPreference(
                            value = true,
                            title = string(R.string.prefs_pure_black),
                            testTag = TestTags.Settings.PURE_BLACK,
                            visible = false,
                            onValueChanged = {},
                        ),
                    ),
                ),
                Preference.PreferenceGroup(
                    title = string(R.string.prefs_security),
                    visible = false,
                    preferenceItems = listOf(
                        Preference.PreferenceItem.SwitchPreference(
                            value = true,
                            title = string(R.string.prefs_secure_screen),
                            testTag = TestTags.Settings.SECURE_SCREEN,
                            onValueChanged = {},
                        ),
                    ),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    value = false,
                    title = string(R.string.prefs_haptics),
                    testTag = TestTags.Settings.HAPTICS,
                    onValueChanged = {},
                ),
            )
        }
        val heading = compose.onNode(hasText(string(R.string.prefs_appearance)) and isHeading())
        heading.assertIsDisplayed()
        tagged(TestTags.Settings.FULL_SCREEN).assertIsDisplayed()
        tagged(TestTags.Settings.HAPTICS).assertIsDisplayed()
        assertEquals(0, count(hasTestTag(TestTags.Settings.PURE_BLACK)))
        assertEquals(0, count(hasTestTag(TestTags.Settings.SECURE_SCREEN)))
        assertEquals(0, count(hasText(string(R.string.prefs_security))))
        val headingBottom = heading.fetchSemanticsNode().boundsInRoot.bottom
        val itemTop = tagged(TestTags.Settings.FULL_SCREEN).fetchSemanticsNode().boundsInRoot.top
        assertTrue(headingBottom <= itemTop)
        compose.onNodeWithText(string(R.string.prefs_haptics)).assertIsDisplayed()
    }
}
