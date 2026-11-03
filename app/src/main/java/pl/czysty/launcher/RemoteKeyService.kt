package pl.czysty.launcher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Usługa ułatwień dostępu dla dwóch przycisków pilota Xiaomi, które system obsługuje sam,
 * więc zwykła aplikacja ich nie dostaje:
 *  - z kafelkami (KEYCODE_ALL_APPS): ekran główny;
 *  - z kółkiem (KEYCODE_HOME): przyspieszacz, czyli ekran główny + zamknięcie aplikacji w tle.
 * Wszystkie inne klawisze przepuszcza bez zmian. Nie czyta zawartości okien.
 */
class RemoteKeyService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var boosting = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_ALL_APPS -> if (event.isReleased()) performGlobalAction(GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_HOME -> if (event.isReleased()) boost()
            else -> return false
        }
        return true
    }

    private fun KeyEvent.isReleased() = action == KeyEvent.ACTION_UP && !isCanceled

    /**
     * Najpierw ekran główny: bieżąca aplikacja przechodzi wtedy do tła i da się ją zamknąć.
     * killBackgroundProcesses zamyka tylko procesy w tle; to, co gra jako usługa pierwszoplanowa
     * (np. muzyka), zostaje. Bez roota nie da się zrobić „wymuszonego zatrzymania” ani czyścić cache.
     */
    private fun boost() {
        if (boosting) return
        boosting = true
        val am = getSystemService(ActivityManager::class.java)
        val before = availableMemory(am)
        performGlobalAction(GLOBAL_ACTION_HOME)
        main.postDelayed({ Booster.show(this, "Przyspieszanie…") }, 250)
        main.postDelayed({
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                for (pkg in launchablePackages()) {
                    try {
                        am.killBackgroundProcesses(pkg)
                    } catch (_: Exception) {
                    }
                }
                // Chwila na zwolnienie pamięci przez zamknięte procesy.
                Thread.sleep(800)
                val after = availableMemory(am)
                val freedMb = ((after - before) / MB).coerceAtLeast(0)
                val freeMb = after / MB
                val text = if (freedMb >= 5) "Zwolniono $freedMb MB · wolne $freeMb MB"
                else "Wszystko już było zamknięte · wolne $freeMb MB"
                main.post {
                    boosting = false
                    Booster.show(this, text)
                }
            }, "booster").start()
        }, HOME_TRANSITION_MS)
    }

    private fun availableMemory(am: ActivityManager) = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem

    /** Aplikacje z ekranu „Wszystkie aplikacje”, bez launchera i aplikacji systemowych, których nie ruszamy. */
    private fun launchablePackages(): Set<String> {
        val result = HashSet<String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .mapTo(result) { it.activityInfo.packageName }
        }
        result.remove(packageName)
        result.removeAll(PROTECTED)
        return result
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        private const val MB = 1024L * 1024L
        private const val HOME_TRANSITION_MS = 900L
        private val PROTECTED = setOf(
            "com.android.tv.settings",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.katniss",
            "com.android.systemui",
        )
    }
}

/** Komunikat przyspieszacza: na ekranie głównym szklana pigułka, a gdy launcher nie jest widoczny, zwykły Toast. */
object Booster {
    /** Ustawiane przez MainActivity, gdy ekran główny jest widoczny (wątek główny). */
    var listener: ((String) -> Unit)? = null

    fun show(service: AccessibilityService, text: String) {
        listener?.invoke(text) ?: Toast.makeText(service, text, Toast.LENGTH_SHORT).show()
    }
}
