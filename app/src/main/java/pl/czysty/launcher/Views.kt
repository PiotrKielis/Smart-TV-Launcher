package pl.czysty.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.View

/** Wspólne zachowanie elementów sterowanych pilotem: OK / przytrzymanie, powiększenie przy fokusie. */
abstract class FocusableView(context: Context) : View(context) {
    /** Wywoływane przy zmianie fokusu (np. by pokazać podpis pod dokiem). */
    var onFocus: ((View, Boolean) -> Unit)? = null
    protected abstract val keys: OkKeyTracker
    protected open val scalesOnFocus = true

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener { performOk() }
    }

    protected abstract fun performOk()

    override fun hasOverlappingRendering() = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = keys.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = keys.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) keys.reset()
        onFocusStyle(gainFocus)
        if (scalesOnFocus) animateFocus(gainFocus)
        invalidate()
        onFocus?.invoke(this, gainFocus)
    }

    protected open fun onFocusStyle(focused: Boolean) {}
}

/** Kafelek aplikacji: gotowa bitmapa + cienka obwódka; po najechaniu biała ramka 3u i skala 1.1. */
@SuppressLint("ViewConstructor")
class AppTileView(
    context: Context,
    val app: AppEntry,
    private val onLaunch: (AppEntry) -> Unit,
    private val onMenu: (AppTileView) -> Unit,
) : FocusableView(context) {
    override val keys = OkKeyTracker({ onLaunch(app) }, { onMenu(this) })

    private val rect = RectF()
    private val radius = Ui.TILE_RADIUS.u
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GLASS_FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.u.coerceAtLeast(1f)
        color = Ui.GLASS_RIM
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.u
        color = Ui.FOCUS
    }
    private val ringGap = 2f.u

    init {
        contentDescription = app.label
        setOnLongClickListener { onMenu(this); true }
    }

    override fun performOk() = onLaunch(app)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        val bmp = app.tile
        if (bmp != null) canvas.drawBitmap(bmp, null, rect, bitmapPaint)
        else canvas.drawRoundRect(rect, radius, radius, placeholder)
        if (isFocused) {
            // Ramka na zewnątrz kafelka, z odstępem: widoczna także na białych banerach.
            // Rodzice mają clipChildren = false, więc można rysować poza granicami widoku.
            val out = ringGap + ring.strokeWidth / 2f
            rect.set(-out, -out, w + out, h + out)
            canvas.drawRoundRect(rect, radius + out, radius + out, ring)
        } else {
            val inset = rim.strokeWidth / 2f
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, radius - inset, radius - inset, rim)
        }
    }
}

/** Szklany przycisk z ikoną. Po najechaniu: biały z ciemną ikoną. */
@SuppressLint("ViewConstructor")
class GlassButton(
    context: Context,
    iconRes: Int,
    val label: String,
    private val iconSizeU: Int,
    private val radiusU: Float,
    private val action: () -> Unit,
) : FocusableView(context) {
    override val keys = OkKeyTracker(action)

    private val icon: Drawable = context.getDrawable(iconRes)!!.mutate().apply { setTint(Ui.TEXT) }
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.u.coerceAtLeast(1f)
        color = Ui.GLASS_RIM
    }

    init {
        contentDescription = label
    }

    override fun performOk() = action()

    override fun onFocusStyle(focused: Boolean) {
        icon.setTint(if (focused) Ui.DARK else Ui.TEXT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = iconSizeU.u
        val l = (w - s) / 2
        val t = (h - s) / 2
        icon.setBounds(l, t, l + s, t + s)
    }

    override fun onDraw(canvas: Canvas) {
        val r = radiusU.u
        val inset = rim.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)
        fill.color = if (isFocused) Ui.FOCUS else Ui.GLASS_FILL
        canvas.drawRoundRect(rect, r, r, fill)
        if (!isFocused) canvas.drawRoundRect(rect, r, r, rim)
        icon.draw(canvas)
    }
}

/**
 * Tapeta na cały ekran. Podmiana obrazu (ostry ↔ rozmyty) to tylko invalidate(): w przeciwieństwie
 * do zmiany tła okna nie wywołuje relayoutu całego drzewa widoków.
 */
class WallpaperView(context: Context) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    var bitmap: android.graphics.Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, null, dst, paint)
    }
}

/** Pozycja menu: tekst, po najechaniu białe tło i ciemny tekst. */
@SuppressLint("ViewConstructor")
class MenuItemView(
    context: Context,
    text: String,
    private val action: () -> Unit,
) : FocusableView(context) {
    override val keys = OkKeyTracker(action)
    override val scalesOnFocus = false

    var text: String = text
        set(value) {
            field = value
            invalidate()
        }

    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.FOCUS }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f.u
        typeface = Ui.regular
    }

    override fun performOk() = action()

    override fun onDraw(canvas: Canvas) {
        if (isFocused) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, 10f.u, 10f.u, fill)
        }
        textPaint.color = if (isFocused) Ui.DARK else Ui.TEXT
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, 16f.u, y, textPaint)
    }
}
