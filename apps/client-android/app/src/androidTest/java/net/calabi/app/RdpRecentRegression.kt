package net.calabi.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import net.calabi.app.rdp.RdpRecent
import net.calabi.app.rdp.RdpRecentStore

internal fun verifyRdpRecent(context: Context) {
    val preferences = "rdp-recent-regression"
    val prefs = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
    check(prefs.edit().clear().commit())
    val image = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
    try {
        val store = RdpRecentStore(context, preferences)
        val first = RdpRecent("device-a", "Fixture A", "100.64.0.8", 3389, 1000)
        store.record("account-a/org-a", first)
        store.record("account-a/org-a", first.copy(port = 3390, usedAt = 2000))
        store.record("account-a/org-a", first.copy(name = "Renamed A", usedAt = 3000))
        check(store.load("account-a/org-a").map { it.port } == listOf(3389, 3390))
        check(store.load("account-a/org-a").first().name == "Renamed A")
        check(store.load("account-a/org-b").isEmpty())
        check(store.load("account-b/org-a").isEmpty())
        store.record("account-b/org-a", first)
        check(store.savePreview("account-a/org-a", first, image))
        check(store.previewFile("account-a/org-a", first) != store.previewFile("account-b/org-a", first))
        check(store.loadPreview("account-b/org-a", first) == null)
        check(store.loadPreview("account-a/org-a", first.copy(port = 3390)) == null)
        store.loadPreview("account-a/org-a", first)!!.let { decoded ->
            try { check(decoded.width == 320 && decoded.height == 180); check(Color.green(decoded.getPixel(160, 90)) > 240) }
            finally { decoded.recycle() }
        }
        check(store.load("account-a/org-a").first().previewVersion > 0)
        store.remove("account-a/org-a", first)
        check(!store.previewFile("account-a/org-a", first).exists())
        check(!store.savePreview("account-a/org-a", first, image))
        check(RdpRecentStore(context, preferences).load("account-a/org-a").single().port == 3390)
        check(store.load("account-b/org-a").single() == first)
        repeat(25) {
            val entry = first.copy(deviceKey = "device-$it", usedAt = 4000L + it)
            store.record("account-a/org-a", entry)
            if (it == 0) check(store.savePreview("account-a/org-a", entry, image))
        }
        check(store.load("account-a/org-a").size == 20)
        check(store.load("account-a/org-a").first().deviceKey == "device-24")
        check(store.load("account-a/org-a").last().deviceKey == "device-5")
        check(!store.previewFile("account-a/org-a", first.copy(deviceKey = "device-0")).exists())
        check(runCatching { store.record("account-a/org-a", first.copy(port = 0)) }.isFailure)
    } finally {
        image.recycle()
        check(prefs.edit().clear().commit())
        File(context.noBackupFilesDir, "$preferences-previews").deleteRecursively()
    }
}
