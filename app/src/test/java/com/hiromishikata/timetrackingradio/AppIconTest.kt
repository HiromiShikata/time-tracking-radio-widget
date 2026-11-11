package com.hiromishikata.timetrackingradio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

internal fun Context.resourceIdOf(type: String, name: String): Int {
    val id = resources.getIdentifier(name, type, packageName)
    assertNotEquals("$type/$name is missing", 0, id)
    return id
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun theApplicationIconResolvesToTheLauncherMipmap() {
        assertEquals(context.resourceIdOf("mipmap", "ic_launcher"), context.applicationInfo.icon)
    }

    @Test
    fun theManifestDeclaresTheLauncherMipmapAsRoundIcon() {
        val application = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("application")
            .item(0) as Element
        assertEquals("@mipmap/ic_launcher_round", application.getAttributeNS(ANDROID_NAMESPACE, "roundIcon"))
        assertEquals("@mipmap/ic_launcher", application.getAttributeNS(ANDROID_NAMESPACE, "icon"))
    }

    @Test
    fun eachLauncherIconIsAnAdaptiveIconWithBackgroundForegroundAndMonochromeLayers() {
        listOf("ic_launcher", "ic_launcher_round").forEach { name ->
            val drawable = context.getDrawable(context.resourceIdOf("mipmap", name))
            assertTrue("$name is not an adaptive icon", drawable is AdaptiveIconDrawable)
            val icon = drawable as AdaptiveIconDrawable
            assertNotNull("$name background", icon.background)
            assertNotNull("$name foreground", icon.foreground)
            assertNotNull("$name monochrome", icon.monochrome)
        }
    }

    @Test
    fun theLauncherBackgroundIsBlackAndTheForegroundIsTheWhiteFourRingGlyph() {
        val icon = context.getDrawable(context.resourceIdOf("mipmap", "ic_launcher")) as AdaptiveIconDrawable
        assertEquals(Color.BLACK, (icon.background as ColorDrawable).color)
        val pixels = render(icon.foreground, LAUNCHER_SIZE)
        assertWhiteOnly(pixels)
        // 2x2 grid of rings centred at (39,39), (69,39), (39,69), (69,69); only the top-left one holds a dot.
        assertEquals("top-left centre dot", Color.WHITE, pixels.getPixel(39, 39))
        assertEquals("top-left ring", Color.WHITE, pixels.getPixel(39, 30))
        assertEquals("top-left gap between dot and ring", 0, Color.alpha(pixels.getPixel(39, 33)))
        assertEquals("top-right hollow centre", 0, Color.alpha(pixels.getPixel(69, 39)))
        assertEquals("top-right ring", Color.WHITE, pixels.getPixel(69, 30))
        assertEquals("bottom-left hollow centre", 0, Color.alpha(pixels.getPixel(39, 69)))
        assertEquals("bottom-right hollow centre", 0, Color.alpha(pixels.getPixel(69, 69)))
        assertEquals("group centre", 0, Color.alpha(pixels.getPixel(54, 54)))
        assertEquals("corner", 0, Color.alpha(pixels.getPixel(0, 0)))
        assertAllGlyphPixelsInsideSafeZone(pixels)
    }

    @Test
    fun theMonochromeLayerIsTheSameWhiteFourRingGlyph() {
        val icon = context.getDrawable(context.resourceIdOf("mipmap", "ic_launcher_round")) as AdaptiveIconDrawable
        val pixels = render(icon.monochrome!!, LAUNCHER_SIZE)
        assertWhiteOnly(pixels)
        assertEquals("top-left centre dot", Color.WHITE, pixels.getPixel(39, 39))
        assertEquals("top-left gap between dot and ring", 0, Color.alpha(pixels.getPixel(39, 33)))
        assertEquals("top-right hollow centre", 0, Color.alpha(pixels.getPixel(69, 39)))
        assertEquals("top-right ring", Color.WHITE, pixels.getPixel(69, 30))
        assertEquals("bottom-right hollow centre", 0, Color.alpha(pixels.getPixel(69, 69)))
    }

    @Test
    fun theNotificationSmallIconIsAWhiteOnlyFourRingGlyph() {
        val drawable = context.getDrawable(context.resourceIdOf("drawable", "ic_notification_radio"))
        assertNotNull(drawable)
        val pixels = render(drawable!!, NOTIFICATION_SIZE)
        assertWhiteOnly(pixels)
        // Viewport 53 units rendered at 96px: ring centres at about (21,21), (75,21), (21,75), (75,75).
        assertEquals("top-left centre dot", Color.WHITE, pixels.getPixel(20, 20))
        assertEquals("top-left ring", Color.WHITE, pixels.getPixel(20, 4))
        assertEquals("top-left gap between dot and ring", 0, Color.alpha(pixels.getPixel(20, 10)))
        assertEquals("top-right hollow centre", 0, Color.alpha(pixels.getPixel(75, 20)))
        assertEquals("top-right ring", Color.WHITE, pixels.getPixel(75, 4))
        assertEquals("bottom-left hollow centre", 0, Color.alpha(pixels.getPixel(20, 75)))
        assertEquals("bottom-right hollow centre", 0, Color.alpha(pixels.getPixel(75, 75)))
        assertEquals("corner", 0, Color.alpha(pixels.getPixel(0, 0)))
    }

    private fun assertAllGlyphPixelsInsideSafeZone(bitmap: Bitmap) {
        val centre = LAUNCHER_SIZE / 2.0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) == 0) continue
                val dx = x + 0.5 - centre
                val dy = y + 0.5 - centre
                assertTrue("pixel ($x,$y) lies outside the 66dp safe zone", Math.hypot(dx, dy) <= SAFE_ZONE_RADIUS + 0.5)
            }
        }
    }

    private fun render(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun assertWhiteOnly(bitmap: Bitmap) {
        var opaquePixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) == 0) continue
                opaquePixels++
                if (Color.red(pixel) < WHITE_THRESHOLD ||
                    Color.green(pixel) < WHITE_THRESHOLD ||
                    Color.blue(pixel) < WHITE_THRESHOLD
                ) {
                    fail("pixel ($x,$y) is ${Integer.toHexString(pixel)}, expected white with alpha only")
                }
            }
        }
        assertTrue("no glyph pixels were drawn", opaquePixels > 0)
    }

    private companion object {
        const val LAUNCHER_SIZE = 108
        const val NOTIFICATION_SIZE = 96
        const val SAFE_ZONE_RADIUS = 33.0
        const val WHITE_THRESHOLD = 250
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
