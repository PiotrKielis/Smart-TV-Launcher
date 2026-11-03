package pl.czysty.launcher

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Zdekodowana tapeta, wspólna dla ekranu głównego i wygaszacza. Żyje tyle co proces:
 * powrót na ekran główny i włączenie wygaszacza nie dekodują obrazu ponownie.
 * Metody load* wołać z wątku w tle.
 */
object Wallpaper {
    @Volatile
    var sharp: Bitmap? = null
        private set

    @Volatile
    var blurred: Bitmap? = null
        private set

    fun loadSharp(res: Resources): Bitmap? = sharp ?: decode(res, R.raw.bg).also { sharp = it }

    fun loadBlurred(res: Resources): Bitmap? = blurred ?: decode(res, R.raw.bg_blur).also { blurred = it }

    private fun decode(res: Resources, id: Int): Bitmap? = try {
        res.openRawResource(id).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }
}
