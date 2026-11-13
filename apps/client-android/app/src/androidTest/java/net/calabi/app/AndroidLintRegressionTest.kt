package net.calabi.app

import android.app.Instrumentation
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import net.calabi.app.rdp.RdpViewport
import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs

/** Checks the warning fixes using installed resources and Android framework behavior. */
class AndroidLintRegressionTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val summary = verifyAndroidTranslations(targetContext)
            verifyRdpPorts(targetContext)
            verifyRdpRecent(targetContext)
            val info = targetContext.applicationInfo
            check(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
            val domains = setOf("root", "file", "database", "sharedpref", "external",
                "device_root", "device_file", "device_database", "device_sharedpref")
            fun exclusions(id: Int): Map<String, Set<String>> {
                val entries = mutableMapOf<String, MutableSet<String>>()
                var section = ""
                targetContext.resources.getXml(id).use { xml ->
                    while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                        if (xml.eventType == XmlPullParser.START_TAG) {
                            when (xml.name) {
                                "full-backup-content", "cloud-backup", "device-transfer" -> section = xml.name
                                "exclude" -> {
                                    check(xml.getAttributeValue(null, "path") == ".")
                                    entries.getOrPut(section) { mutableSetOf() }.add(xml.getAttributeValue(null, "domain"))
                                }
                                "include" -> error("Backup must not include phone identity or credentials")
                            }
                        }
                        xml.next()
                    }
                }
                return entries
            }
            check(exclusions(R.xml.backup_rules) == mapOf("full-backup-content" to domains))
            check(exclusions(R.xml.data_extraction_rules) == mapOf("cloud-backup" to domains, "device-transfer" to domains))
            val scanner = targetContext.packageManager.getActivityInfo(
                ComponentName(targetContext, "com.journeyapps.barcodescanner.CaptureActivity"), 0)
            check(scanner.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            // OEM launchers can wrap the package-manager icon in a themed bitmap.
            // Check our adaptive resource and the system-provided icon separately.
            val resourceIcon = targetContext.getDrawable(R.mipmap.ic_launcher)
            check(resourceIcon is AdaptiveIconDrawable) { "Unexpected launcher resource: ${resourceIcon?.javaClass?.name}" }
            val icons = listOf(resourceIcon, targetContext.packageManager.getApplicationIcon(info))
            val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
            try {
                for (icon in icons) {
                    bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                    icon.setBounds(0, 0, 108, 108)
                    icon.draw(Canvas(bitmap))
                    val pixels = IntArray(108 * 108)
                    bitmap.getPixels(pixels, 0, 108, 0, 0, 108, 108)
                    check(pixels.toSet().size > 1) { "Launcher icon is blank: ${icon.javaClass.name}" }
                }
            } finally { bitmap.recycle() }
            val viewport = RdpViewport().apply { configure(1000, 800, 1280, 720) }
            fun roundTrip() {
                val x = viewport.localX(640f)
                val y = viewport.localY(360f)
                val remote = checkNotNull(viewport.remote(x, y, true))
                check(abs(remote.x - 640f) < 0.01f && abs(remote.y - 360f) < 0.01f)
            }
            roundTrip()
            viewport.scale(2f); viewport.pan(80f, -40f); roundTrip()
            viewport.configure(500, 1200, 1280, 720); roundTrip()
            viewport.actualPixels(); roundTrip()
            result.putString("stream", summary + "RDP port bounds, JNI creation and encrypted per-port credential isolation passed.\nRecent connections: persistence, scope/port isolation, deduplication, ordering and removal passed.\nBackup exclusions, merged scanner orientation, adaptive/system icons (${icons.last().javaClass.simpleName}) and RDP cursor transforms passed.\n")
            finish(0, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(1, result)
        }
    }
}
