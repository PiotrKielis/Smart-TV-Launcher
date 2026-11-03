package pl.czysty.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var repo: AppRepository
    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var apps: List<AppEntry> = emptyList()
    private var loadedOnce = false

    private lateinit var root: FrameLayout
    private lateinit var wallpaperView: WallpaperView
    private var windowBackgroundCleared = false
    private lateinit var homeLayer: FrameLayout
    private lateinit var gridLayer: FrameLayout
    private lateinit var dateView: TextView
    private lateinit var clockView: TextView
    private lateinit var netIcon: ImageView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherText: TextView
    private lateinit var weatherDivider: View
    private lateinit var weather: Weather
    private val weatherFetching = AtomicBoolean(false)
    private var netState = -1
    private lateinit var dockScroll: HorizontalScrollView
    private lateinit var dockRow: LinearLayout
    private lateinit var dockLabel: TextView
    private lateinit var boostPill: TextView
    private val hideBoostPill = Runnable {
        boostPill.animate().alpha(0f).setDuration(200).withEndAction { boostPill.visibility = View.INVISIBLE }.start()
    }
    private lateinit var allAppsButton: GlassButton
    private lateinit var gridScroll: ScrollView
    private lateinit var gridContent: LinearLayout
    private val dockTiles = ArrayList<AppTileView>()
    private val gridTiles = ArrayList<AppTileView>()

    private var gridVisible = false
    private var gridShowsHidden = false
    private var gridDirty = true
    private var menuOverlay: View? = null

    // ---------- cykl życia ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        Ui.init(this)
        repo = AppRepository(applicationContext)
        weather = Weather(applicationContext)
        // Odświeżanie listy przy każdym powrocie na ekran główny nie może zabierać CPU nawigacji.
        workerThread = HandlerThread("apps", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        worker = Handler(workerThread.looper)

        buildUi()
        setContentView(root)
        applyBackground()
        // Tapetę dekodujemy po pierwszej klatce, żeby nie opóźniała pojawienia się doku
        // (pierwszy post wykonuje się przy podpięciu okna, drugi dopiero po narysowaniu klatki).
        root.post { root.post { loadWallpaper() } }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        register(packageReceiver, filter)
    }

    override fun onStart() {
        super.onStart()
        updateClock()
        register(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        val cm = getSystemService(ConnectivityManager::class.java)
        updateNetwork(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        showWeather(weather.cached())
        Booster.listener = ::showBoostMessage
        main.post(weatherTask)
    }

    override fun onResume() {
        super.onResume()
        // Nowe aplikacje ze Sklepu Play pojawiają się po każdym powrocie na ekran główny.
        refreshApps()
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(weatherTask)
        Booster.listener = null
        unregisterReceiver(timeReceiver)
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageReceiver)
        workerThread.quitSafely()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Przycisk Home na ekranie głównym: wróć do doku.
        closeMenu(null)
        if (gridVisible) closeGrid()
        dockScroll.scrollTo(0, 0)
        dockRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Android TV 9 używa klasycznego Wstecz")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            menuOverlay != null -> closeMenu(lastMenuTarget)
            gridVisible && gridShowsHidden -> openGrid(hidden = false)
            gridVisible -> closeGrid()
            // Na ekranie głównym Wstecz nic nie robi: launcher się nie zamyka.
        }
    }

    private fun showBoostMessage(text: String) {
        main.removeCallbacks(hideBoostPill)
        boostPill.animate().cancel()
        boostPill.text = text
        boostPill.visibility = View.VISIBLE
        boostPill.animate().alpha(1f).setDuration(150).start()
        main.postDelayed(hideBoostPill, 3000)
    }

    /** W siatce strzałka w górę z pierwszego rzędu działa jak Wstecz (powrót na ekran główny). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gridVisible && menuOverlay == null && event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            val focused = currentFocus
            val next = focused?.focusSearch(View.FOCUS_UP)
            if (focused != null && (next == null || !isInGrid(next))) {
                // Przytrzymana strzałka zatrzymuje się na górze; wyjście tylko po osobnym wciśnięciu.
                if (event.repeatCount == 0) {
                    @Suppress("DEPRECATION")
                    onBackPressed()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInGrid(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === gridContent) return true
            p = p.parent as? View
        }
        return false
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    // ---------- tapeta ----------

    private fun loadWallpaper() {
        if (Wallpaper.sharp != null && Wallpaper.blurred != null) return
        Thread({
            Wallpaper.loadSharp(resources)
            main.post { if (!isDestroyed) applyBackground() }
            Wallpaper.loadBlurred(resources)
            main.post { if (!isDestroyed && gridVisible) applyBackground() }
        }, "wallpaper").start()
    }

    /** Jeden statyczny obraz na cały ekran; przy siatce natychmiastowa podmiana na rozmytą wersję. */
    private fun applyBackground() {
        val bmp = if (gridVisible) Wallpaper.blurred ?: Wallpaper.sharp else Wallpaper.sharp
        wallpaperView.bitmap = bmp
        if (bmp != null && !windowBackgroundCleared) {
            // Czarne tło okna potrzebne jest tylko do chwili załadowania tapety; potem to zbędne
            // dodatkowe wypełnienie całego ekranu w każdej klatce.
            window.setBackgroundDrawable(null)
            windowBackgroundCleared = true
        }
    }

    // ---------- układ ----------

    private fun buildUi() {
        root = FrameLayout(this)
        wallpaperView = WallpaperView(this)
        root.addView(wallpaperView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Data i duży zegar u góry na środku (zostają też nad siatką aplikacji).
        dateView = Ui.text(this, 18f, Ui.TEXT, Ui.regular).apply { gravity = Gravity.CENTER }
        root.addView(dateView, frameLp(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP, top = 46.u))
        clockView = Ui.text(this, 104f, 0xEEFFFFFF.toInt(), Ui.thin).apply { gravity = Gravity.CENTER }
        root.addView(clockView, frameLp(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP, top = 70.u))

        // Szklana pigułka w prawym górnym rogu: pogoda w Krakowie | stan sieci.
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.glassBackground(17f)
            setPadding(14.u, 0, 14.u, 0)
        }
        weatherIcon = ImageView(this).apply { visibility = View.GONE }
        pill.addView(weatherIcon, linearLp(18.u, 18.u))
        weatherText = Ui.text(this, 14f, Ui.TEXT, Ui.medium).apply { visibility = View.GONE }
        pill.addView(weatherText, linearLp(WRAP_CONTENT, WRAP_CONTENT, start = 7.u))
        weatherDivider = View(this).apply {
            setBackgroundColor(Ui.GLASS_RIM)
            visibility = View.GONE
        }
        pill.addView(weatherDivider, linearLp(1.u.coerceAtLeast(1), 16.u, start = 12.u, end = 12.u))
        netIcon = ImageView(this).apply { setImageResource(R.drawable.ic_wifi) }
        pill.addView(netIcon, linearLp(18.u, 18.u))
        root.addView(pill, frameLp(WRAP_CONTENT, 34.u, Gravity.TOP or Gravity.END, top = 30.u, end = 36.u))

        // Komunikat przyspieszacza (przycisk z kółkiem), między zegarem a dokiem.
        boostPill = Ui.text(this, 15f, Ui.TEXT, Ui.medium).apply {
            gravity = Gravity.CENTER
            background = Ui.glassBackground(18f, 0x59000000)
            setPadding(20.u, 0, 20.u, 0)
            alpha = 0f
            visibility = View.INVISIBLE
        }
        root.addView(boostPill, frameLp(WRAP_CONTENT, 36.u, Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = 226.u))

        homeLayer = FrameLayout(this)
        root.addView(homeLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        buildDockArea()
        buildQuickRow()

        gridLayer = FrameLayout(this).apply {
            visibility = View.INVISIBLE
            forceHasOverlappingRendering(false)
        }
        gridScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 14.u, 0, 40.u)
        }
        gridContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // clipChildren przycina dziecko do jego własnych granic: ScrollView nie może przycinać zawartości
        // (ramka fokusu w pierwszym rzędzie wchodzi w górny margines), a przycina dopiero gridLayer,
        // do granic przewijanego obszaru, żeby przewinięte rzędy nie wchodziły na zegar.
        noClip(gridScroll, gridContent)
        gridScroll.addView(gridContent, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gridLayer.addView(gridScroll, frameLp(MATCH_PARENT, MATCH_PARENT, top = 200.u))
        root.addView(gridLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun buildDockArea() {
        dockScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(24.u, 12.u, 24.u, 12.u)
        }
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dockScroll.addView(dockRow, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        homeLayer.addView(dockScroll, frameLp(MATCH_PARENT, (Ui.TILE_H + 24).u, top = DOCK_TOP.u))
        noClip(homeLayer, dockScroll, dockRow)
        dockRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> repositionDockLabel() }
        dockScroll.setOnScrollChangeListener { _, _, _, _, _ -> repositionDockLabel() }

        allAppsButton = GlassButton(this, R.drawable.ic_all_apps, "Wszystkie aplikacje", 26, 16f) { openGrid(hidden = false) }
        allAppsButton.onFocus = ::onDockFocus
        dockRow.addView(allAppsButton, linearLp(Ui.TILE_H.u, Ui.TILE_H.u, start = 26.u, end = 6.u))

        // Nazwa aplikacji pod kafelkiem tylko po najechaniu. Stały rozmiar: zmiana tekstu nie wywołuje relayoutu.
        dockLabel = Ui.text(this, 15f, Ui.TEXT, Ui.medium).apply { gravity = Gravity.CENTER }
        homeLayer.addView(dockLabel, frameLp(LABEL_W.u, 22.u, top = (DOCK_TOP + 12 + Ui.TILE_H + 12).u))
    }

    private fun buildQuickRow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val items = listOf<Triple<String, Int, () -> Unit>>(
            Triple("Sieć", R.drawable.ic_wifi) { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            Triple("Bluetooth", R.drawable.ic_bluetooth) { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
            Triple("Obraz i dźwięk", R.drawable.ic_tune) {
                openSettings(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SOUND_SETTINGS)
            },
            Triple("Wygaszacz", R.drawable.ic_moon) { startActivity(Intent(this, ScreensaverActivity::class.java)) },
            // MANAGE_APPLICATIONS ma na Sticku jeden ekran; APPLICATION_SETTINGS pasuje do dwóch (okno wyboru).
            Triple("Aplikacje", R.drawable.ic_apps) {
                openSettings(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS)
            },
            Triple("Ustawienia", R.drawable.ic_settings) { openSettings(Settings.ACTION_SETTINGS) },
        )
        val buttons = ArrayList<View>()
        for ((label, icon, action) in items) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val button = GlassButton(this, icon, label, 22, 14f, action)
            buttons += button
            cell.addView(button, linearLp(112.u, 46.u))
            cell.addView(
                Ui.text(this, 12.5f, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER; text = label },
                linearLp(112.u, WRAP_CONTENT, top = 8.u),
            )
            noClip(cell)
            row.addView(cell, linearLp(WRAP_CONTENT, WRAP_CONTENT, start = 8.u, end = 8.u))
        }
        noClip(row)
        lockRowEnds(buttons.first(), buttons.last())
        homeLayer.addView(row, frameLp(MATCH_PARENT, WRAP_CONTENT, top = QUICK_TOP.u))
    }

    // ---------- dok ----------

    private fun buildDock() {
        val focused = currentFocus
        val focusedPkg = (focused as? AppTileView)?.takeIf { it.parent === dockRow }?.app?.pkg
        val focusedIndex = if (focusedPkg != null) dockRow.indexOfChild(focused) else -1
        // Usuwamy tylko kafelki: przyciski na końcu zostają (i zachowują fokus).
        while (dockRow.childCount > 0 && dockRow.getChildAt(0) is AppTileView) dockRow.removeViewAt(0)
        dockTiles.clear()
        val favorites = repo.favorites(apps)
        // Przy więcej niż 5 ulubionych kafelki się zmniejszają, żeby dok mieścił się na ekranie
        // (do minimalnej szerokości; dalej dok się przewija).
        val avail = 960 - 2 * 24 - (Ui.TILE_H + 26 + 6) - 6
        val tileW = minOf(Ui.TILE_W, maxOf(DOCK_MIN_TILE_W, avail / maxOf(favorites.size, 1) - 12))
        val tileH = tileW * 9 / 16
        favorites.forEachIndexed { i, app ->
            val tile = AppTileView(this, app, ::launch, ::showMenu).apply { onFocus = ::onDockFocus }
            dockTiles += tile
            dockRow.addView(tile, i, linearLp(tileW.u, tileH.u, start = 6.u, end = 6.u))
        }
        allAppsButton.layoutParams = allAppsButton.layoutParams.apply {
            width = tileH.u
            height = tileH.u
        }
        lockRowEnds(dockTiles.firstOrNull() ?: allAppsButton, allAppsButton)
        if (focusedPkg != null && menuOverlay == null) {
            val target = dockTiles.firstOrNull { it.app.pkg == focusedPkg }
                ?: dockRow.getChildAt(focusedIndex.coerceAtMost(dockTiles.size - 1).coerceAtLeast(0))
            target?.requestFocus()
        }
    }

    private fun onDockFocus(v: View, focused: Boolean) {
        if (!focused) {
            dockLabel.text = ""
            return
        }
        dockLabel.text = when (v) {
            is AppTileView -> v.app.label
            is GlassButton -> v.label
            else -> ""
        }
        positionDockLabel(v)
    }

    /**
     * Podpis wyśrodkowany pod elementem doku. Liczone z pozycji w układzie (bez skali, której środek
     * i tak się nie przesuwa). Wołane też po relayoucie i przewinięciu doku: po przebudowie doku
     * fokus trafia na kafelek, zanim zostanie rozmieszczony.
     */
    private fun positionDockLabel(v: View) {
        var center = v.left + v.width / 2f
        var p = v.parent as? View
        while (p != null && p !== homeLayer) {
            center += p.left - p.scrollX
            p = p.parent as? View
        }
        dockLabel.translationX = center - LABEL_W.u / 2f
    }

    private fun repositionDockLabel() {
        dockRow.focusedChild?.let { positionDockLabel(it) }
    }

    // ---------- lista aplikacji ----------

    private val refreshTask = Runnable {
        val list = repo.loadApps()
        main.post { onAppsLoaded(list) }
        repo.loadTiles(list) { app -> main.post { onTileReady(app) } }
    }

    private fun refreshApps() {
        worker.removeCallbacks(refreshTask)
        worker.post(refreshTask)
    }

    private fun onAppsLoaded(list: List<AppEntry>) {
        if (isDestroyed) return
        val changed = list.size != apps.size || list.indices.any { !list[it].sameAs(apps[it]) }
        apps = list
        if (!changed && loadedOnce) return
        buildDock()
        gridDirty = true
        if (gridVisible && menuOverlay == null) rebuildGridKeepFocus()
        else prebuildGridWhenIdle()
        if (!loadedOnce) {
            loadedOnce = true
            val f = currentFocus
            if (f == null || f === allAppsButton) dockRow.getChildAt(0)?.requestFocus()
        }
    }

    private fun onTileReady(app: AppEntry) {
        for (t in dockTiles) if (t.app === app) t.invalidate()
        for (t in gridTiles) if (t.app === app) t.invalidate()
    }

    private fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            val fallback = packageManager.getLeanbackLaunchIntentForPackage(app.pkg)
                ?: packageManager.getLaunchIntentForPackage(app.pkg)
            try {
                startActivity(fallback!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                Toast.makeText(this, "Nie można uruchomić: ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Otwiera pierwszy istniejący ekran ustawień z listy, a w ostateczności ogólne Ustawienia. */
    private fun openSettings(vararg actions: String) {
        for (action in actions.toList() + Settings.ACTION_SETTINGS) {
            // Wskazujemy ekran wprost: gdy akcji odpowiada kilka ekranów, system pokazałby okno „Wykonaj czynność przez…”.
            val match = packageManager.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull()?.activityInfo ?: continue
            val intent = Intent(action)
                .setClassName(match.packageName, match.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Ten ekran ustawień jest niedostępny", Toast.LENGTH_SHORT).show()
    }

    // ---------- wszystkie aplikacje ----------

    private fun openGrid(hidden: Boolean) {
        val modeChanged = hidden != gridShowsHidden
        gridShowsHidden = hidden
        // Siatka zostaje zbudowana między otwarciami; przebudowa tylko po zmianie listy lub trybu.
        if (gridDirty || modeChanged) buildGrid()
        if (!gridVisible) {
            gridVisible = true
            applyBackground()
            homeLayer.visibility = View.INVISIBLE
            gridLayer.alpha = 0f
            gridLayer.visibility = View.VISIBLE
            gridLayer.animate().alpha(1f).setDuration(140).start()
        }
        gridScroll.scrollTo(0, 0)
        firstGridFocusable()?.requestFocus()
    }

    private fun closeGrid() {
        gridLayer.animate().cancel()
        gridLayer.visibility = View.INVISIBLE
        gridVisible = false
        if (gridShowsHidden) gridDirty = true
        gridShowsHidden = false
        homeLayer.visibility = View.VISIBLE
        applyBackground()
        allAppsButton.requestFocus()
    }

    private fun buildGrid() {
        gridContent.removeAllViews()
        gridTiles.clear()
        val cells = ArrayList<View>()
        if (gridShowsHidden) {
            val title = Ui.text(this, 16f, Ui.TEXT_DIM).apply {
                gravity = Gravity.CENTER
                text = "Ukryte aplikacje · przytrzymaj OK, aby pokazać · Wstecz wraca"
            }
            gridContent.addView(title, linearLp(MATCH_PARENT, WRAP_CONTENT, bottom = 16.u))
            repo.hiddenApps(apps).forEach { cells += appCell(it) }
            if (cells.isEmpty()) {
                cells += buttonCell(R.drawable.ic_all_apps, "Brak ukrytych · wróć") { openGrid(hidden = false) }
            }
        } else {
            repo.visible(apps).forEach { cells += appCell(it) }
            cells += buttonCell(R.drawable.ic_settings, "Ustawienia") { openSettings(Settings.ACTION_SETTINGS) }
            cells += buttonCell(R.drawable.ic_hidden, "Ukryte (${repo.hiddenApps(apps).size})") { openGrid(hidden = true) }
        }
        val rowWidth = GRID_COLUMNS * (Ui.TILE_W + 2 * GRID_GAP_HALF).u
        cells.chunked(GRID_COLUMNS).forEach { rowCells ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            noClip(row)
            rowCells.forEach { row.addView(it, linearLp(WRAP_CONTENT, WRAP_CONTENT, start = GRID_GAP_HALF.u, end = GRID_GAP_HALF.u)) }
            gridContent.addView(row, linearLp(rowWidth, WRAP_CONTENT, bottom = 14.u))
        }
        gridDirty = false
    }

    /**
     * Siatka (niewidoczna) budowana zawczasu, gdy wątek UI nic nie robi: pierwsze otwarcie
     * „Wszystkich aplikacji” to wtedy tylko zmiana widoczności, bez tworzenia widoków.
     */
    private fun prebuildGridWhenIdle() {
        Looper.myQueue().addIdleHandler {
            if (gridDirty && !gridVisible && !isDestroyed) {
                gridShowsHidden = false
                buildGrid()
            }
            false
        }
    }

    private fun rebuildGridKeepFocus() {
        val pkg = (currentFocus as? AppTileView)?.takeIf { it in gridTiles }?.app?.pkg
        buildGrid()
        val target = gridTiles.firstOrNull { it.app.pkg == pkg } ?: firstGridFocusable()
        target?.requestFocus()
    }

    private fun firstGridFocusable(): View? {
        gridTiles.firstOrNull()?.let { return it }
        val focusables = ArrayList<View>()
        gridContent.addFocusables(focusables, View.FOCUS_DOWN)
        return focusables.firstOrNull()
    }

    private fun appCell(app: AppEntry): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val tile = AppTileView(this, app, ::launch, ::showMenu).apply { onFocus = ::revealGridCell }
        gridTiles += tile
        cell.addView(tile, linearLp(Ui.TILE_W.u, Ui.TILE_H.u))
        cell.addView(gridLabel(app.label), linearLp(Ui.TILE_W.u, WRAP_CONTENT, top = 12.u))
        noClip(cell)
        return cell
    }

    private fun buttonCell(icon: Int, label: String, action: () -> Unit): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val button = GlassButton(this, icon, label, 28, Ui.TILE_RADIUS, action).apply { onFocus = ::revealGridCell }
        cell.addView(button, linearLp(Ui.TILE_W.u, Ui.TILE_H.u))
        cell.addView(gridLabel(label), linearLp(Ui.TILE_W.u, WRAP_CONTENT, top = 12.u))
        noClip(cell)
        return cell
    }

    /** ScrollView sam przewija tylko do kafelka; przewijamy do całej komórki z podpisem i ramką fokusu. */
    private fun revealGridCell(v: View, focused: Boolean) {
        if (!focused) return
        val cell = v.parent as? View ?: return
        // Od góry tyle, ile zajmuje ramka fokusu: większy margines odsłaniałby końcówki podpisów rzędu wyżej.
        cell.requestRectangleOnScreen(Rect(0, -10.u, cell.width, cell.height + 16.u))
    }

    private fun gridLabel(label: String) = Ui.text(this, 13f, Ui.TEXT_DIM).apply {
        gravity = Gravity.CENTER
        text = label
    }

    // ---------- menu przytrzymania OK ----------

    private var lastMenuTarget: String? = null

    private fun showMenu(tile: AppTileView) {
        val app = tile.app
        val inDock = tile.parent === dockRow
        closeMenu(null)
        lastMenuTarget = app.pkg

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glassBackground(20f, Ui.PANEL)
            setPadding(12.u, 16.u, 12.u, 12.u)
        }
        val title = Ui.text(this, 20f, Ui.TEXT, Ui.medium).apply {
            text = app.label
            setPadding(16.u, 0, 16.u, 0)
        }
        panel.addView(title, linearLp(MATCH_PARENT, WRAP_CONTENT, bottom = 10.u))

        fun item(text: String, action: () -> Unit) =
            MenuItemView(this, text, action).also { panel.addView(it, linearLp(MATCH_PARENT, 44.u)) }

        val hidden = repo.isHidden(app.pkg)
        val canMove = inDock || !gridShowsHidden
        if (canMove) {
            // Przesuwanie zostawia menu otwarte, żeby można było przesunąć o kilka pozycji.
            item("Przesuń w lewo") { moveApp(app, inDock, -1) }
            item("Przesuń w prawo") { moveApp(app, inDock, +1) }
        }
        if (!hidden) {
            val fav = repo.isFavorite(apps, app.pkg)
            item(if (fav) "Usuń z ulubionych" else "Dodaj do ulubionych") {
                repo.toggleFavorite(apps, app.pkg)
                buildDock()
                closeMenu(app.pkg)
            }
        }
        item(if (hidden) "Pokaż" else "Ukryj") {
            repo.toggleHidden(apps, app.pkg)
            buildDock()
            gridDirty = true
            if (gridVisible) buildGrid()
            closeMenu(app.pkg)
        }
        item("Informacje o aplikacji") {
            closeMenu(app.pkg)
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
        item("Anuluj") { closeMenu(app.pkg) }

        overlay.addView(panel, frameLp(340.u, WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        menuOverlay = overlay
        panel.getChildAt(1).requestFocus()
        // Fokus nie może uciec z menu na kafelki pod spodem.
        homeLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        gridLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun moveApp(app: AppEntry, inDock: Boolean, delta: Int) {
        if (inDock) {
            if (repo.moveFavorite(apps, app.pkg, delta)) buildDock()
        } else if (repo.moveInGrid(apps, app.pkg, delta)) {
            buildGrid()
        }
    }

    /** Zamyka menu i ustawia fokus na kafelku [focusPkg] (w doku albo siatce, zależnie od tego, co widać). */
    private fun closeMenu(focusPkg: String?) {
        val overlay = menuOverlay ?: return
        homeLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        gridLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (gridVisible && gridDirty) buildGrid()
        if (focusPkg != null) {
            val tiles = if (gridVisible) gridTiles else dockTiles
            val target: View? = tiles.firstOrNull { it.app.pkg == focusPkg }
                ?: if (gridVisible) firstGridFocusable() else dockRow.getChildAt(0)
            target?.requestFocus()
        }
        root.removeView(overlay)
        menuOverlay = null
    }

    // ---------- zegar i sieć ----------

    private fun updateClock() {
        val now = Date()
        clockView.text = Clock.time(now)
        dateView.text = Clock.date(now)
    }

    private fun updateNetwork(caps: NetworkCapabilities?) {
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        // Na Wi-Fi zmiana siły sygnału też wywołuje onCapabilitiesChanged: przerysowujemy tylko przy zmianie stanu.
        val state = if (caps == null) 0 else if (ethernet) 2 else 1
        if (state == netState) return
        netState = state
        netIcon.setImageResource(if (ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        netIcon.alpha = if (caps == null) 0.35f else 1f
        netIcon.contentDescription = when {
            caps == null -> "Brak sieci"
            ethernet -> "Ethernet"
            else -> "Wi-Fi"
        }
        // Sieć wróciła, a pogoda jest nieaktualna: pobierz od razu, nie czekając na timer.
        if (caps != null && weather.cached()?.isStale != false) requestWeather()
    }

    /** Co 5 minut sprawdza tylko, czy minęło 30 minut od ostatniego pobrania; sama pogoda idzie z sieci rzadko. */
    private val weatherTask = object : Runnable {
        override fun run() {
            if (weather.cached()?.isStale != false) requestWeather()
            main.postDelayed(this, WEATHER_CHECK_MS)
        }
    }

    private fun requestWeather() {
        if (!weatherFetching.compareAndSet(false, true)) return
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val now = weather.fetch()
            weatherFetching.set(false)
            if (now != null) main.post { if (!isDestroyed) showWeather(now) }
        }, "weather").start()
    }

    private fun showWeather(now: Weather.Now?) {
        val visibility = if (now == null) View.GONE else View.VISIBLE
        weatherIcon.visibility = visibility
        weatherText.visibility = visibility
        weatherDivider.visibility = visibility
        if (now == null) return
        weatherIcon.setImageResource(now.icon)
        weatherText.text = now.text
        weatherIcon.contentDescription = "Kraków ${now.text}"
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            main.post { updateNetwork(caps) }
        }

        override fun onLost(network: Network) {
            main.post { updateNetwork(null) }
        }
    }

    companion object {
        private const val DOCK_TOP = 286
        private const val QUICK_TOP = 418
        private const val LABEL_W = 360
        private const val DOCK_MIN_TILE_W = 96
        private const val WEATHER_CHECK_MS = 5 * 60 * 1000L
        private const val GRID_COLUMNS = 5
        private const val GRID_GAP_HALF = 12
    }
}
