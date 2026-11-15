package dev.kodakiko.ui.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier as JavaModifier

@RunWith(RobolectricTestRunner::class)
class TestTagsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun constants(type: Class<*>): List<String> =
        type.declaredFields
            .filter { JavaModifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { field ->
                field.isAccessible = true
                field.get(null) as String
            } +
            type.declaredClasses.flatMap { constants(it) }

    private val values: List<String> by lazy { constants(TestTags::class.java) }

    @Test
    fun theFixtureHoldsEveryStaticIdOfAKeptV031Flow() {
        assertEquals(615, UiFixtures.testTags.getValue("tagCount").jsonPrimitive.int)
        assertEquals(615, UiFixtures.keptTags.distinct().size)
        assertTrue(UiFixtures.keptTags.containsAll(listOf("gallery.tile", "capture.shutter", "calendar.day", "recoveryKey.word", "errors.invalidCredentials", "sync.dead")))
    }

    @Test
    fun everyKeptIdIsATestTagsConstantWithTheSameString() {
        val missing = UiFixtures.keptTags.filterNot { it in values.toSet() }
        assertTrue("${missing.size} missing, first: ${missing.take(25)}", missing.isEmpty())
    }

    @Test
    fun testTagsHoldOnlyNonBlankIdsWithoutWhitespace() {
        val bad = values.filter { it.isBlank() || it.any(Char::isWhitespace) }
        assertTrue("bad ids: $bad", bad.isEmpty())
    }

    @Test
    fun listAndCalendarDayIdsAreBuiltLikeV031() {
        assertEquals("gallery.tile.0", TestTags.listItem("gallery.tile", 0))
        assertEquals("recoveryKey.confirmField.2", TestTags.listItem("recoveryKey.confirmField", 2))
        assertEquals("capture.trayItem.12", TestTags.listItem("capture.trayItem", 12))
        assertEquals("calendar.day.2026-11-09", TestTags.calendarDay("2026-11-09"))
    }

    @Test
    fun testTagsAreExposedAsResourceIdsBelowTheRootModifier() {
        compose.setContent {
            Box(Modifier.testTagsAsResourceIdRoot()) {
                Box(Modifier.testTag(TestTags.listItem("gallery.tile", 0)).size(1.dp))
            }
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsPropertiesAndroid.TestTagsAsResourceId, true)).assertExists()
        compose.onNodeWithTag("gallery.tile.0").assertExists()
    }
}
