package org.starbridge.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.MediaStore
import org.starbridge.core.imaging.ImagingHost
import org.starbridge.imaging.Picture
import java.io.ByteArrayOutputStream
import java.io.File

/** JPEG encoding, phone temperature and battery, and saving exports where the user finds them. */
class AndroidImagingHost(private val context: Context) : ImagingHost {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    override fun jpeg(picture: Picture, quality: Int): ByteArray {
        val bmp = Bitmap.createBitmap(picture.argb, picture.width, picture.height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream(picture.width * picture.height / 4)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        return out.toByteArray()
    }

    override fun thermalStatus(): Int = runCatching { power.currentThermalStatus }.getOrDefault(-1)

    override fun battery(): Pair<Int, Boolean>? = runCatching {
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..100) null else level to battery.isCharging
    }.getOrNull()

    /** Copies the file to Downloads/StarBridge (visible in Files and over USB). */
    override fun publish(file: File, mime: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/StarBridge")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "la app (no se pudo copiar a Descargas)"
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        return "Descargas/StarBridge"
    }
}
