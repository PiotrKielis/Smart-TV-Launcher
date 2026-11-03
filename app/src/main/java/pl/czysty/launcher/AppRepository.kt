package pl.czysty.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.text.Collator
import java.util.Locale

class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val leanback: Boolean,
    val updateTime: Long,
) {
    /** Gotowy kafelek (baner albo ikona na szkle) w docelowym rozmiarze, z zaokrąglonymi rogami. */
    @Volatile
    var tile: Bitmap? = null

    fun sameAs(o: AppEntry) = pkg == o.pkg && component == o.component && label == o.label && updateTime == o.updateTime
}

/**
 * Lista aplikacji, kolejność, ulubione i ukryte. [loadApps] i [loadTiles] wołać z wątku w tle,
 * resztę z wątku głównego. Kafelki są renderowane raz i trzymane w pamięci oraz na dysku,
 * więc po restarcie procesu nie trzeba ładować zasobów innych aplikacji.
 */
class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val tileDir = File(context.filesDir, "tiles").apply { mkdirs() }
    private val indexFile = File(tileDir, "index.tsv")
    private val memory = HashMap<String, AppEntry>()
    private var diskIndex: MutableMap<String, IndexRow>? = null
    private val collator = Collator.getInstance(Locale("pl", "PL"))

    private class IndexRow(val updateTime: Long, val size: String, val label: String)

    private val tileW get() = Ui.TILE_W.u
    private val tileH get() = Ui.TILE_H.u
    private val sizeKey get() = "${tileW}x$tileH"

    // ---------- ładowanie (wątek w tle) ----------

    fun loadApps(): List<AppEntry> {
        val found = LinkedHashMap<String, Pair<ResolveInfo, Boolean>>()
        for ((category, leanback) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val p = ri.activityInfo.packageName
                if (p != context.packageName && p !in found) found[p] = ri to leanback
            }
        }
        val index = index()
        var indexChanged = false
        val result = ArrayList<AppEntry>(found.size)
        for ((pkg, pair) in found) {
            val (ri, leanback) = pair
            val component = ComponentName(pkg, ri.activityInfo.name)
            val updateTime = try {
                pm.getPackageInfo(pkg, 0).lastUpdateTime
            } catch (e: Exception) {
                0L
            }
            val cached = memory[pkg]
            if (cached != null && cached.updateTime == updateTime && cached.component == component) {
                result += cached
                continue
            }
            val row = index[pkg]?.takeIf { it.updateTime == updateTime }
            val label = row?.label ?: ri.loadLabel(pm).toString().trim()
            val entry = AppEntry(pkg, component, label, leanback, updateTime)
            if (row != null && row.size == sizeKey) {
                entry.tile = BitmapFactory.decodeFile(File(tileDir, "$pkg.png").path)
            }
            if (row == null || row.label != label) {
                index[pkg] = IndexRow(updateTime, if (row?.size == sizeKey && entry.tile != null) sizeKey else "", label)
                indexChanged = true
            }
            memory[pkg] = entry
            result += entry
        }
        memory.keys.retainAll(found.keys)
        if (index.keys.retainAll(found.keys)) indexChanged = true
        if (indexChanged) saveIndex()
        return result
    }

    /** Renderuje brakujące kafelki; [onTile] dostaje każdy gotowy kafelek (z wątku w tle). */
    fun loadTiles(apps: List<AppEntry>, onTile: (AppEntry) -> Unit) {
        val index = index()
        var changed = false
        for (app in apps) {
            if (app.tile != null) continue
            val ri = resolve(app) ?: continue
            val bmp = try {
                renderTile(ri)
            } catch (e: Exception) {
                null
            } ?: continue
            app.tile = bmp
            try {
                File(tileDir, "${app.pkg}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                index[app.pkg] = IndexRow(app.updateTime, sizeKey, app.label)
                changed = true
            } catch (_: Exception) {
            }
            onTile(app)
        }
        if (changed) saveIndex()
        tileDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".png") && f.name.removeSuffix(".png") !in index) f.delete()
        }
    }

    private fun resolve(app: AppEntry): ResolveInfo? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
        return pm.resolveActivity(intent, 0)
    }

    private fun renderTile(ri: ResolveInfo): Bitmap {
        val w = tileW
        val h = tileH
        val r = Ui.TILE_RADIUS.u
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val banner = ri.activityInfo.loadBanner(pm)
        if (banner != null) {
            // Baner skalujemy raz tutaj (z filtrowaniem), a rogi zaokrąglamy shaderem: przy rysowaniu
            // kafelek to już tylko drawBitmap bez skalowania.
            val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            banner.setBounds(0, 0, w, h)
            banner.draw(Canvas(tmp))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(tmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            canvas.drawRoundRect(rect, r, r, p)
            tmp.recycle()
        } else {
            val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GLASS_FILL }
            canvas.drawRoundRect(rect, r, r, glass)
            val icon = ri.loadIcon(pm)
            val s = (h * 0.62f).toInt()
            val left = (w - s) / 2
            val top = (h - s) / 2
            icon.setBounds(left, top, left + s, top + s)
            icon.draw(canvas)
        }
        return out
    }

    private fun index(): MutableMap<String, IndexRow> {
        diskIndex?.let { return it }
        val map = HashMap<String, IndexRow>()
        try {
            if (indexFile.exists()) indexFile.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size == 4) map[parts[0]] = IndexRow(parts[1].toLongOrNull() ?: 0L, parts[2], parts[3])
            }
        } catch (_: Exception) {
        }
        diskIndex = map
        return map
    }

    private fun saveIndex() {
        val map = diskIndex ?: return
        try {
            val tmp = File(tileDir, "index.tmp")
            tmp.bufferedWriter().use { w ->
                for ((pkg, row) in map) {
                    val label = row.label.replace('\t', ' ').replace('\n', ' ')
                    w.write("$pkg\t${row.updateTime}\t${row.size}\t$label\n")
                }
            }
            tmp.renameTo(indexFile)
        } catch (_: Exception) {
        }
    }

    // ---------- kolejność, ulubione, ukryte (wątek główny) ----------

    private fun readList(key: String): List<String> =
        prefs.getString(key, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    private fun writeList(key: String, list: List<String>) {
        prefs.edit().putString(key, list.joinToString("\n")).apply()
    }

    val hidden: Set<String> get() = readList(KEY_HIDDEN).toSet()

    fun isHidden(pkg: String) = pkg in hidden

    private fun alphabetical(apps: List<AppEntry>) = apps.sortedWith { a, b -> collator.compare(a.label, b.label) }

    /** Wszystkie aplikacje w kolejności użytkownika; nowe (spoza zapisanej kolejności) alfabetycznie na końcu. */
    fun ordered(apps: List<AppEntry>): List<AppEntry> {
        val byPkg = apps.associateBy { it.pkg }
        val order = readList(KEY_ORDER)
        val head = order.mapNotNull { byPkg[it] }
        val headSet = head.mapTo(HashSet()) { it.pkg }
        return head + alphabetical(apps.filter { it.pkg !in headSet })
    }

    fun visible(apps: List<AppEntry>): List<AppEntry> {
        val h = hidden
        return ordered(apps).filter { it.pkg !in h }
    }

    fun hiddenApps(apps: List<AppEntry>): List<AppEntry> {
        val h = hidden
        return alphabetical(apps.filter { it.pkg in h })
    }

    /** Ulubione do doku. Dopóki użytkownik ich nie zmieni: pierwsze 5 widocznych aplikacji alfabetycznie. */
    fun favorites(apps: List<AppEntry>): List<AppEntry> {
        if (!prefs.contains(KEY_FAVORITES)) {
            val h = hidden
            return alphabetical(apps.filter { it.pkg !in h }).take(DEFAULT_FAVORITES)
        }
        val byPkg = apps.associateBy { it.pkg }
        return readList(KEY_FAVORITES).mapNotNull { byPkg[it] }
    }

    fun isFavorite(apps: List<AppEntry>, pkg: String) = favorites(apps).any { it.pkg == pkg }

    fun toggleFavorite(apps: List<AppEntry>, pkg: String) {
        val fav = favorites(apps).map { it.pkg }.toMutableList()
        if (!fav.remove(pkg)) fav += pkg
        writeList(KEY_FAVORITES, fav)
    }

    fun moveFavorite(apps: List<AppEntry>, pkg: String, delta: Int): Boolean {
        val fav = favorites(apps).map { it.pkg }.toMutableList()
        if (!swap(fav, pkg, delta)) return false
        writeList(KEY_FAVORITES, fav)
        return true
    }

    fun moveInGrid(apps: List<AppEntry>, pkg: String, delta: Int): Boolean {
        val vis = visible(apps).map { it.pkg }.toMutableList()
        if (!swap(vis, pkg, delta)) return false
        val h = hidden
        writeList(KEY_ORDER, vis + ordered(apps).map { it.pkg }.filter { it in h })
        return true
    }

    fun toggleHidden(apps: List<AppEntry>, pkg: String) {
        val h = readList(KEY_HIDDEN).toMutableList()
        if (!h.remove(pkg)) {
            h += pkg
            val fav = favorites(apps).map { it.pkg }
            if (pkg in fav) writeList(KEY_FAVORITES, fav - pkg)
        }
        writeList(KEY_HIDDEN, h)
    }

    private fun swap(list: MutableList<String>, pkg: String, delta: Int): Boolean {
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return false
        list[i] = list[j]
        list[j] = pkg
        return true
    }

    companion object {
        private const val KEY_ORDER = "order"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HIDDEN = "hidden"
        private const val DEFAULT_FAVORITES = 5
    }
}
