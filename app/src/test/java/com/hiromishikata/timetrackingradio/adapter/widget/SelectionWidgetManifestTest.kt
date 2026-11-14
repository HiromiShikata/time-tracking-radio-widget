package com.hiromishikata.timetrackingradio.adapter.widget

import com.hiromishikata.timetrackingradio.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val MANIFEST_ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class SelectionWidgetManifestTest {
    private val receivers: List<Element> = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .getElementsByTagName("receiver")
        .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun receiverNamed(name: String): Element =
        receivers.single { it.getAttributeNS(MANIFEST_ANDROID_NAMESPACE, "name") == name }

    @Test
    fun theCellTapReceiverIsNotExportedAndDeclaresNoIntentFilter() {
        val receiver = receiverNamed(".adapter.widget.SelectionCellTapReceiver")
        assertEquals("false", receiver.getAttributeNS(MANIFEST_ANDROID_NAMESPACE, "exported"))
        assertEquals(0, receiver.getElementsByTagName("action").length)
    }

    @Test
    fun theWidgetProviderStaysExportedAndItsOnlyIntentFilterActionIsAppWidgetUpdate() {
        val provider = receiverNamed(".adapter.widget.SelectionWidgetProvider")
        assertEquals("true", provider.getAttributeNS(MANIFEST_ANDROID_NAMESPACE, "exported"))
        val actions = provider.getElementsByTagName("action")
        assertEquals(listOf("android.appwidget.action.APPWIDGET_UPDATE"), (0 until actions.length).map {
            (actions.item(it) as Element).getAttributeNS(MANIFEST_ANDROID_NAMESPACE, "name")
        })
    }
}
