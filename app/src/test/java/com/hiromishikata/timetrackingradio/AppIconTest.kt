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
    fun theLauncherBackgroundIsBlackAndTheForegroundIsTheWhiteRadioGlyph() {
        val icon = context.getDrawable(context.resourceIdOf("mipmap", "ic_launcher")) as AdaptiveIconDrawable
        assertEquals(Color.BLACK, (icon.background as ColorDrawable).color)
        val pixels = render(icon.foreground, LAUNCHER_SIZE)
        assertWhiteOnly(pixels)
        assertEquals("centre dot", Color.WHITE, pixels.getPixel(54, 54))
        assertEquals("ring", Color.WHITE, pixels.getPixel(54, 33))
        assertEquals("hole between ring and dot", 0, Color.alpha(pixels.getPixel(54, 40)))
        assertEquals("corner", 0, Color.alpha(pixels.getPixel(0, 0)))
    }

    @Test
    fun theNotificationSmallIconIsAWhiteOnlyRadioGlyph() {
        val drawable = context.getDrawable(context.resourceIdOf("drawable", "ic_notification_radio"))
        assertNotNull(drawable)
        val pixels = render(drawable!!, NOTIFICATION_SIZE)
        assertWhiteOnly(pixels)
        assertEquals("centre dot", Color.WHITE, pixels.getPixel(48, 48))
        assertEquals("hole between ring and dot", 0, Color.alpha(pixels.getPixel(48, 20)))
        assertEquals("corner", 0, Color.alpha(pixels.getPixel(0, 0)))
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
        const val WHITE_THRESHOLD = 250
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
