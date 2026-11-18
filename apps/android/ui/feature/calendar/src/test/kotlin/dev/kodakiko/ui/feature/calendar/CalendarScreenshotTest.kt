package dev.kodakiko.ui.feature.calendar

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.kodakiko.data.db.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class CalendarScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String, state: CalendarState, appTheme: AppTheme = AppTheme.LIGHT) {
        compose.show(appTheme) { CalendarContent(state = state, onAction = {}) }
        compose.onRoot().captureScreenshot("calendar/$name")
    }

    @Test
    fun monthWithCoversTodayRingAndDimmedNeighbors() = capture("month_covers", CalendarStates.state(days = CalendarStates.november))

    @Test
    fun monthWithCoversDark() = capture("month_covers_dark", CalendarStates.state(days = CalendarStates.november), AppTheme.DARK)

    @Test
    fun emptyMonth() = capture("month_empty", CalendarStates.state(month = "2026-10", days = emptyMap(), canGoNext = true))

    @Test
    fun futureMonthWithNextDisabled() = capture(
        "future_month_next_disabled",
        CalendarStates.state(
            month = "2027-01",
            months = listOf("2026-10", "2026-11", "2027-01"),
            days = mapOf("2027-01-15" to CalendarDay(2, "f", "f-media")),
            canGoNext = false,
        ),
    )

    @Test
    fun februaryStartingOnSunday() = capture(
        "february_2026",
        CalendarStates.state(month = "2026-02", months = listOf("2026-01", "2026-02", "2026-03"), days = mapOf("2026-02-14" to CalendarDay(1, "v", "v-media")), today = "2026-02-20"),
    )

    @Test
    fun jumpSheetWithError() {
        compose.show { CalendarContent(state = CalendarStates.state(jump = CalendarJump("2026-12", CalendarJumpError.EMPTY)), onAction = {}) }
        captureScreenRoboImage(Screenshots.path("calendar/jump_empty_month"), Screenshots.options("calendar/jump_empty_month"))
    }

    @Test
    fun refreshing() = capture("refreshing", CalendarStates.state(days = CalendarStates.november, refreshing = true))
}
