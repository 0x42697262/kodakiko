package dev.kodakiko.ui.core

import android.content.Context
import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AppLocaleTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private fun updated(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.bulk_updated, count, count)

    @Test
    fun thePinnedLocaleIsUsEnglish() {
        assertEquals(Locale.US, AppLocale.LOCALE)
        assertEquals("en-US", AppLocale.LOCALE.toLanguageTag())
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun aFrenchDeviceStillGetsEnglishPluralRules() {
        assertEquals(Locale.FRANCE, app.resources.configuration.locales[0])
        assertEquals("0 entry updated.", updated(app, 0))
        val pinned = AppLocale.wrap(app)
        assertEquals(Locale.US, pinned.resources.configuration.locales[0])
        assertEquals(1, pinned.resources.configuration.locales.size())
        assertEquals("0 entries updated.", updated(pinned, 0))
        assertEquals("1 entry updated.", updated(pinned, 1))
        assertEquals("2 entries updated.", updated(pinned, 2))
    }

    @Test
    @Config(qualifiers = "ar-rEG")
    fun anArabicDeviceGetsEnglishPluralsAndLeftToRightLayout() {
        assertEquals(View.LAYOUT_DIRECTION_RTL, app.resources.configuration.layoutDirection)
        val pinned = AppLocale.wrap(app)
        assertEquals(Locale.US, pinned.resources.configuration.locales[0])
        assertEquals(View.LAYOUT_DIRECTION_LTR, pinned.resources.configuration.layoutDirection)
        assertEquals("0 entries updated.", updated(pinned, 0))
        assertEquals("1 entry updated.", updated(pinned, 1))
        assertEquals("2 entries updated.", updated(pinned, 2))
        assertEquals("11 entries updated.", updated(pinned, 11))
        assertEquals("Delete 1 entry permanently? This can't be undone.", pinned.resources.getQuantityString(R.plurals.bulk_confirm_delete_title, 1, 1))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun composePluralsResolveThroughThePinnedContext() {
        compose.setContent {
            val pinned = AppLocale.wrap(LocalContext.current)
            CompositionLocalProvider(LocalContext provides pinned, LocalConfiguration provides pinned.resources.configuration) {
                Text(pluralStringResource(R.plurals.bulk_updated, 0, 0), modifier = Modifier.testTag("plural"))
            }
        }
        compose.onNodeWithTag("plural").assertTextEquals("0 entries updated.")
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun anEnglishDeviceIsUnchanged() {
        val pinned = AppLocale.wrap(app)
        assertEquals(Locale.US, pinned.resources.configuration.locales[0])
        assertEquals("0 entries updated.", updated(pinned, 0))
        assertEquals(string(R.string.app_loading), pinned.getString(R.string.app_loading))
    }
}
