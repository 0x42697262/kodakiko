package dev.kodakiko.ui.core.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.ui.core.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityThemeAndIconTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val iconSize = 1024

    private fun attribute(attr: Int): TypedValue {
        val themed = ContextThemeWrapper(context, R.style.Theme_Kodakiko)
        val value = TypedValue()
        assertTrue("attribute $attr is not set by Theme.Kodakiko", themed.theme.resolveAttribute(attr, value, true))
        return value
    }

    private fun color(value: TypedValue): Long {
        assertTrue("not a color: type ${value.type}", value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
        return value.data.toLong() and 0xFFFFFFFFL
    }

    private fun flag(value: TypedValue): Boolean {
        assertEquals(TypedValue.TYPE_INT_BOOLEAN, value.type)
        return value.data != 0
    }

    @Test
    @Config(qualifiers = "notnight")
    fun activityThemeHasNoActionBarAndTheLightBackground() {
        assertEquals(false, flag(attribute(android.R.attr.windowActionBar)))
        assertEquals(true, flag(attribute(android.R.attr.windowNoTitle)))
        assertEquals(V031Palette.LIGHT.getValue("background"), color(attribute(android.R.attr.windowBackground)))
    }

    @Test
    @Config(qualifiers = "night")
    fun activityThemeUsesTheDarkBackgroundInDarkMode() {
        assertEquals(false, flag(attribute(android.R.attr.windowActionBar)))
        assertEquals(true, flag(attribute(android.R.attr.windowNoTitle)))
        assertEquals(V031Palette.DARK.getValue("background"), color(attribute(android.R.attr.windowBackground)))
    }

    @Test
    fun launcherIconIsAdaptiveWithTheV031BackgroundColor() {
        assertEquals(V031Palette.ICON_BACKGROUND, context.getColor(R.color.ic_launcher_background).toLong() and 0xFFFFFFFFL)
        val icon = context.getDrawable(R.mipmap.ic_launcher)
        assertTrue("ic_launcher is ${icon?.javaClass?.name}", icon is AdaptiveIconDrawable)
        val background = (icon as AdaptiveIconDrawable).background
        assertTrue("background layer is ${background?.javaClass?.name}", background is ColorDrawable)
        assertEquals(V031Palette.ICON_BACKGROUND, (background as ColorDrawable).color.toLong() and 0xFFFFFFFFL)
    }

    private fun renderedForeground(): Bitmap {
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val foreground = requireNotNull(icon.foreground)
        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        foreground.setBounds(0, 0, iconSize, iconSize)
        foreground.draw(Canvas(bitmap))
        return bitmap
    }

    private fun assertPixel(bitmap: Bitmap, xFraction: Double, yFraction: Double, expected: Long, label: String) {
        val pixel = bitmap.getPixel((xFraction * iconSize).roundToInt(), (yFraction * iconSize).roundToInt())
        val actual = pixel.toLong() and 0xFFFFFFFFL
        val close = (0 until 4).all { channel ->
            val shift = channel * 8
            abs(((actual shr shift) and 0xFF) - ((expected shr shift) and 0xFF)) <= 2
        }
        assertTrue("$label: expected ${expected.toString(16)} but was ${actual.toString(16)}", close)
    }

    @Test
    fun launcherIconForegroundIsTheV031SvgArtworkScaledFor108dpMasks() {
        val bitmap = renderedForeground()
        assertPixel(bitmap, 0.02, 0.02, 0x00000000, "transparent corner")
        assertPixel(bitmap, 0.98, 0.98, 0x00000000, "transparent corner")
        assertPixel(bitmap, 0.41500, 0.34195, 0xFF1F4D45, "book page (svg 400,300)")
        assertPixel(bitmap, 0.34859, 0.47477, 0xFFD9962A, "clasp band (svg 300,500)")
        assertPixel(bitmap, 0.54781, 0.72711, 0xFFFFFFFF, "camera body (svg 600,880)")
    }
}
