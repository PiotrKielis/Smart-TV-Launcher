package pl.czysty.launcher

import android.view.KeyEvent

/**
 * OK na pilocie: krótkie wciśnięcie = klik, przytrzymanie (repeatCount > 0) = menu.
 * Klik liczy się tylko wtedy, gdy ten sam widok widział też wciśnięcie klawisza,
 * więc puszczenie OK po przytrzymaniu nie „klika” w pierwszą pozycję menu.
 */
class OkKeyTracker(
    private val onClick: () -> Unit,
    private val onLongPress: (() -> Unit)? = null,
) {
    private var downSeen = false
    private var longFired = false

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && onLongPress != null) {
            if (event.repeatCount == 0) onLongPress.invoke()
            return true
        }
        if (!isOk(keyCode)) return false
        if (event.repeatCount == 0) {
            downSeen = true
            longFired = false
        } else if (downSeen && !longFired && onLongPress != null) {
            longFired = true
            onLongPress.invoke()
        }
        return true
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && onLongPress != null) return true
        if (!isOk(keyCode)) return false
        val click = downSeen && !longFired && !event.isCanceled
        reset()
        if (click) onClick()
        return true
    }

    fun reset() {
        downSeen = false
        longFired = false
    }

    companion object {
        fun isOk(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
    }
}
