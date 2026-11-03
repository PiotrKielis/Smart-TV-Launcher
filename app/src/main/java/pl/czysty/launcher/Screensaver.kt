package pl.czysty.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** Data i godzina po polsku, wspólne dla ekranu głównego i wygaszacza. */
object Clock {
    private val pl = Locale("pl", "PL")
    private val timeFormat = SimpleDateFormat("HH:mm", pl)
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", pl)

    fun time(now: Date = Date()): String {
        timeFormat.timeZone = TimeZone.getDefault()
        return timeFormat.format(now)
    }

    fun date(now: Date = Date()): String {
        dateFormat.timeZone = TimeZone.getDefault()
        return dateFormat.format(now).replaceFirstChar { it.titlecase(pl) }
    }
}

/**
 * Wygaszacz: sama tapeta z datą i godziną, w tym samym miejscu co na ekranie głównym.
 * Co minutę napisy przesuwają się o kilka punktów (ochrona przed wypaleniem); poza tym GPU nic nie robi.
 */
class ScreensaverView(context: Context) : FrameLayout(context) {
    private val wallpaper = WallpaperView(context)
    private val group = FrameLayout(context)
    private val dateView = Ui.text(context, 18f, Ui.TEXT, Ui.regular).apply { gravity = Gravity.CENTER }
    private val clockView = Ui.text(context, 104f, 0xEEFFFFFF.toInt(), Ui.thin).apply { gravity = Gravity.CENTER }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            update()
            shift()
        }
    }

    init {
        addView(wallpaper, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Te same pozycje co na ekranie głównym: data 46 u, zegar 70 u od góry.
        group.addView(dateView, frameLp(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        group.addView(clockView, frameLp(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP, top = 24.u))
        addView(group, frameLp(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP, top = 46.u))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(timeReceiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(timeReceiver, filter)
        val ready = Wallpaper.sharp
        if (ready != null) {
            wallpaper.bitmap = ready
        } else {
            Thread({
                val bmp = Wallpaper.loadSharp(resources)
                post { wallpaper.bitmap = bmp }
            }, "wallpaper").start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(timeReceiver)
    }

    private fun update() {
        val now = Date()
        clockView.text = Clock.time(now)
        dateView.text = Clock.date(now)
    }

    private fun shift() {
        val r = 12.u
        group.animate()
            .translationX(Random.nextInt(-r, r + 1).toFloat())
            .translationY(Random.nextInt(-r, r + 1).toFloat())
            .setDuration(1500)
            .start()
    }
}

/** Wygaszacz uruchamiany przyciskiem z ekranu głównego. Dowolny klawisz go zamyka. */
class ScreensaverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        Ui.init(this)
        setContentView(ScreensaverView(this))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Zamykamy dopiero przy puszczeniu klawisza, żeby nie trafił on w ekran główny pod spodem.
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}

/** Ten sam wygaszacz jako systemowy (Ustawienia → Wygaszacz ekranu albo `settings put secure screensaver_components`). */
class ScreensaverDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        Ui.init(this)
        setContentView(ScreensaverView(this))
    }
}
