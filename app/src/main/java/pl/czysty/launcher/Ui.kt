package pl.czysty.launcher

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Jednostka układu: ekran ma zawsze 960×540 „u”, niezależnie od gęstości,
 * więc proporcje są takie same jak w projekcie (1920×1080 → 1 u = 2 px).
 */
object Ui {
    var unit = 2f
        private set

    fun init(context: Context) {
        val m = context.resources.displayMetrics
        val w = maxOf(m.widthPixels, m.heightPixels)
        val h = minOf(m.widthPixels, m.heightPixels)
        unit = minOf(w / 960f, h / 540f)
    }

    const val GLASS_FILL = 0x24FFFFFF        // biel ~14%
    const val GLASS_RIM = 0x40FFFFFF         // biel ~25%
    const val FOCUS = 0xFFFFFFFF.toInt()
    const val DARK = 0xFF16151F.toInt()
    const val TEXT = 0xF2FFFFFF.toInt()
    const val TEXT_DIM = 0xC8FFFFFF.toInt()
    const val PANEL = 0xEB15131F.toInt()

    const val TILE_W = 128
    const val TILE_H = 72
    const val TILE_RADIUS = 12f
    const val SCALE_FOCUSED = 1.1f
    const val FOCUS_ANIM_MS = 110L

    val regular: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    val medium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    val thin: Typeface by lazy { Typeface.create("sans-serif-thin", Typeface.NORMAL) }

    fun glassBackground(radius: Float, fill: Int = GLASS_FILL) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.u
        setStroke(1.u.coerceAtLeast(1), GLASS_RIM)
    }

    fun text(context: Context, sizeU: Float, color: Int = TEXT, face: Typeface = regular) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeU.u)
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
}

val Int.u: Int get() = (this * Ui.unit + 0.5f).toInt()
val Float.u: Float get() = this * Ui.unit

fun frameLp(w: Int, h: Int, gravity: Int = Gravity.TOP or Gravity.START, top: Int = 0, start: Int = 0, end: Int = 0) =
    FrameLayout.LayoutParams(w, h, gravity).apply {
        topMargin = top
        leftMargin = start
        rightMargin = end
    }

fun linearLp(w: Int, h: Int, start: Int = 0, end: Int = 0, top: Int = 0, bottom: Int = 0) =
    LinearLayout.LayoutParams(w, h).apply {
        leftMargin = start
        rightMargin = end
        topMargin = top
        bottomMargin = bottom
    }

/** Powiększenie przy fokusie: tylko właściwości warstwy rysowania (RenderThread), bez relayoutu. */
fun View.animateFocus(focused: Boolean) {
    val s = if (focused) Ui.SCALE_FOCUSED else 1f
    animate().scaleX(s).scaleY(s).setDuration(Ui.FOCUS_ANIM_MS).start()
}

/**
 * Strzałka w lewo z pierwszego / w prawo z ostatniego elementu rzędu zostaje w miejscu.
 * Bez tego Android szuka kandydata na skos i fokus przeskakuje do innego rzędu.
 */
fun lockRowEnds(first: View, last: View) {
    for (v in listOf(first, last)) if (v.id == View.NO_ID) v.id = View.generateViewId()
    first.nextFocusLeftId = first.id
    last.nextFocusRightId = last.id
}

/** Powiększony kafelek może wystawać poza rodzica. */
fun noClip(vararg groups: android.view.ViewGroup) = groups.forEach {
    it.clipChildren = false
    it.clipToPadding = false
}
