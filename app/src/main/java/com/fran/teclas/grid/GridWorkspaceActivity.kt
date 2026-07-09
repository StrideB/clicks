package com.fran.teclas.grid

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fran.teclas.DockedKeyboardService
import com.fran.teclas.KeyboardSettings
import com.fran.teclas.VivoDockedExperiment
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The grid workspace home surface ("Grid Workspace Lab" / optional "Teclas Grid" home).
 *
 * AOSP-style workspace on top of the system wallpaper: drag to arrange, drop-to-folder, hosted
 * widgets with resize and Pixel-style stacking, and an app drawer whose tiles long-press-drag
 * straight onto the grid. The surface follows the real keyboard placement setting — docked mode
 * reserves the docked keyboard's exact height at the bottom (animated), widget mode goes
 * full-bleed with a summonable keyboard/search strip. Isolated from MainActivity's launcher:
 * separate AppWidgetHost id and persistence.
 */
class GridWorkspaceActivity : Activity(), GridWorkspaceView.Host {

    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var grid: GridWorkspaceView
    private lateinit var gridContainer: FrameLayout
    private lateinit var dockSpacer: LinearLayout
    private lateinit var searchStrip: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var modeButton: TextView
    private lateinit var keyboardButton: TextView
    private var drawerPanel: FrameLayout? = null

    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingProvider: AppWidgetProviderInfo? = null
    private var dockAnimator: ValueAnimator? = null

    // Continuous drawer->grid drag, forwarded by the root before normal dispatch.
    private var externalDragActive = false

    private val ink = 0xFFEDEDED.toInt()
    private val inkDim = 0x8AFFFFFF.toInt()
    private val accent = 0xFF9CE7B4.toInt()
    private val panelBg = 0xF20B0B0E.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "keyboard_placement", "keyboard_size" -> applyMode(animated = true)
            "active_icon_pack" -> { GridIcons.clearCache(); grid.setItems(GridWorkspaceStore.load(this)) }
            else -> if (key?.startsWith("icon_override_") == true) {
                GridIcons.clearCache(); grid.setItems(GridWorkspaceStore.load(this))
            }
        }
    }

    private inner class DragRoot(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (externalDragActive) {
                val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
                val rootLoc = IntArray(2).also { getLocationOnScreen(it) }
                val gx = ev.x + (rootLoc[0] - loc[0])
                val gy = ev.y + (rootLoc[1] - loc[1])
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> grid.externalDragMove(gx, gy)
                    MotionEvent.ACTION_UP -> { grid.externalDragDrop(gx, gy); externalDragActive = false }
                    MotionEvent.ACTION_CANCEL -> { grid.externalDragCancel(); externalDragActive = false }
                }
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = DragRoot(this)
        // Wallpaper shows through the theme; keep the surface readable with a soft scrim.
        root.setBackgroundColor(0x4D000000)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        column.addView(buildHeader(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        grid = GridWorkspaceView(this, this)
        gridContainer = FrameLayout(this).apply {
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        column.addView(gridContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        searchStrip = buildSearchStrip()
        searchStrip.visibility = View.GONE
        column.addView(searchStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dockSpacer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(mono("· DOCKED KEYBOARD ·", 9f, 0x40FFFFFF).apply { letterSpacing = 0.22f })
        }
        column.addView(dockSpacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        applyMode(animated = false)
        grid.setItems(GridWorkspaceStore.load(this))
        getSharedPreferences("teclas", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefListener)
        if (GridWorkspaceStore.load(this).isEmpty()) {
            Toast.makeText(this, "APPS opens the drawer — long-press an app to drag it home", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("teclas", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    // -------------------------------------------------------------------- chrome

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        addView(mono("GRID", 11f, accent).apply { letterSpacing = 0.22f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(headerButton("APPS") { toggleDrawer() })
        addView(headerButton("+ WIDGET") { showWidgetPicker() })
        keyboardButton = headerButton("KBD") { toggleSearchStrip() }
        addView(keyboardButton)
        modeButton = headerButton("…") { togglePlacementMode() }
        addView(modeButton)
    }

    private fun headerButton(label: String, onClick: () -> Unit): TextView = mono(label, 10f, ink).apply {
        letterSpacing = 0.14f
        setPadding(dp(11), dp(8), dp(11), dp(8))
        background = GradientDrawable().apply {
            setColor(0x2E000000); cornerRadius = dp(9).toFloat(); setStroke(dp(1), 0x33FFFFFF)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(7)
        layoutParams = lp
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun mono(text: String, size: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    // ------------------------------------------------------- placement mode sync

    private fun isDocked(): Boolean = KeyboardSettings.isDocked(this)

    /** Mirror of DockedKeyboardService.overlayKeyboardHeight() so the grid clears the overlay. */
    private fun dockedKeyboardHeight(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        val rowH = dp(56 + (size * 20 / 100))
        val overlap = dp(8 + size * 3 / 100)
        return rowH * 4 - overlap * 3 + dp(4) + dp(2)
    }

    private fun togglePlacementMode() {
        val next = if (isDocked()) KeyboardSettings.MODE_WIDGET else KeyboardSettings.MODE_DOCKED
        KeyboardSettings.setPlacementMode(this, next)
        if (next == KeyboardSettings.MODE_WIDGET) {
            VivoDockedExperiment.clearViewportTruncation(this)
            stopService(Intent(this, DockedKeyboardService::class.java))
        } else {
            if (VivoDockedExperiment.isEnabled(this)) VivoDockedExperiment.applyViewportTruncation(this)
            ensureDockedOverlay()
        }
        applyMode(animated = true)
    }

    private fun applyMode(animated: Boolean) {
        val docked = isDocked()
        modeButton.text = if (docked) "DOCKED" else "WIDGET"
        keyboardButton.visibility = if (docked) View.GONE else View.VISIBLE
        if (docked && searchStrip.visibility == View.VISIBLE) hideSearchStrip()
        val target = if (docked) dockedKeyboardHeight() else 0
        val current = dockSpacer.layoutParams.height
        dockAnimator?.cancel()
        if (!animated || current == target) {
            setDockHeight(target)
            return
        }
        dockAnimator = ValueAnimator.ofInt(current, target).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { setDockHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun setDockHeight(h: Int) {
        dockSpacer.layoutParams = (dockSpacer.layoutParams as LinearLayout.LayoutParams).apply { height = h }
        dockSpacer.visibility = if (h == 0) View.GONE else View.VISIBLE
        dockSpacer.requestLayout()
    }

    // --------------------------------------------------- keyboard / search strip

    private fun buildSearchStrip(): LinearLayout {
        searchInput = EditText(this).apply {
            hint = "SEARCH"
            setHintTextColor(0x55FFFFFF)
            setTextColor(ink)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setOnEditorActionListener { _, actionId, event ->
                val isEnter = actionId == EditorInfo.IME_ACTION_SEARCH ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (isEnter && text.isNotBlank()) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", text.toString()))
                    }.onFailure {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                            "https://www.google.com/search?q=" + android.net.Uri.encode(text.toString()))))
                    }
                    setText("")
                    hideSearchStrip()
                    true
                } else false
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(panelBg)
                cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dp(1), 0x26FFFFFF)
            }
            addView(mono("›", 14f, accent).apply { setPadding(0, 0, dp(10), 0) })
            addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(headerButton("✕") { hideSearchStrip() })
        }
    }

    private fun toggleSearchStrip() {
        if (searchStrip.visibility == View.VISIBLE) hideSearchStrip() else {
            searchStrip.visibility = View.VISIBLE
            searchInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSearchStrip() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchStrip.visibility = View.GONE
    }

    override fun onBackPressed() {
        when {
            drawerPanel?.visibility == View.VISIBLE -> closeDrawer()
            searchStrip.visibility == View.VISIBLE -> hideSearchStrip()
            // As a HOME activity, back should do nothing further.
            else -> {}
        }
    }

    // ----------------------------------------------------------------- app drawer

    private fun toggleDrawer() {
        if (drawerPanel?.visibility == View.VISIBLE) closeDrawer() else openDrawer()
    }

    private fun closeDrawer() {
        drawerPanel?.animate()?.translationY(gridContainer.height.toFloat())?.setDuration(180)
            ?.withEndAction { drawerPanel?.visibility = View.GONE }?.start()
    }

    private fun openDrawer() {
        val panel = drawerPanel ?: buildDrawer().also {
            drawerPanel = it
            gridContainer.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        panel.visibility = View.VISIBLE
        panel.translationY = gridContainer.height.toFloat()
        panel.animate().translationY(0f).setDuration(200).start()
    }

    private fun buildDrawer(): FrameLayout {
        val la = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        val activities = la.getActivityList(null, Process.myUserHandle())
            .sortedBy { it.label.toString().lowercase() }

        val cols = 5
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(12), dp(10), dp(24)) }
        var row: LinearLayout? = null
        activities.forEachIndexed { index, info ->
            if (index % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val item = GridItem(
                id = "app-${SystemClock.uptimeMillis()}-$index",
                type = GridItemType.APP,
                cellX = 0, cellY = 0,
                packageName = info.componentName.packageName,
                className = info.componentName.className,
                label = info.label.toString(),
            )
            row!!.addView(drawerTile(item), LinearLayout.LayoutParams(0, dp(86), 1f))
        }

        val scroll = ScrollView(this).apply { addView(rows) }
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(panelBg)
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(mono("APPS — TAP TO LAUNCH · HOLD TO PLACE", 8.5f, inkDim).apply {
                    letterSpacing = 0.16f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(2))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            visibility = View.GONE
        }
    }

    private fun drawerTile(item: GridItem): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ImageView(context).apply {
                setImageDrawable(loadIcon(item.packageName, item.className))
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(mono((item.label ?: "").let { if (it.length > 9) it.take(8) + "…" else it }.uppercase(), 8f, ink).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(2), dp(4), dp(2), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isClickable = true
            isLongClickable = true
            setOnClickListener { launchApp(item) }
            setOnLongClickListener {
                // Hand the in-flight gesture to the grid: hide the drawer, spawn the ghost tile
                // under the finger, and let DragRoot forward the rest of this touch stream.
                closeDrawer()
                val fresh = item.copy(id = "app-${System.currentTimeMillis()}")
                externalDragActive = true
                val gridLoc = IntArray(2).also { grid.getLocationOnScreen(it) }
                val tileLoc = IntArray(2).also { getLocationOnScreen(it) }
                grid.startExternalDrag(
                    fresh,
                    (tileLoc[0] - gridLoc[0] + width / 2).toFloat(),
                    (tileLoc[1] - gridLoc[1] + height / 2).toFloat(),
                )
                true
            }
        }
    }

    // ----------------------------------------------------------- Host callbacks

    override fun onStart() {
        super.onStart()
        runCatching { appWidgetHost.startListening() }
    }

    override fun onResume() {
        super.onResume()
        // Unlike MainActivity (which renders its own keyboard on home and stops the overlay),
        // the grid home relies on the docked overlay service for its keyboard — keep it alive
        // whenever this surface is visible in docked mode.
        ensureDockedOverlay()
    }

    private fun ensureDockedOverlay() {
        if (!isDocked()) return
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, DockedKeyboardService::class.java))
        } else {
            Toast.makeText(this, "Allow Teclas overlay so the docked keyboard can show.", Toast.LENGTH_LONG).show()
            runCatching { startActivity(DockedKeyboardService.overlaySettingsIntent(this)) }
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { appWidgetHost.stopListening() }
    }

    override fun launchApp(item: GridItem) {
        val pkg = item.packageName ?: return
        val cls = item.className
        val intent = if (cls != null) Intent().setClassName(pkg, cls) else packageManager.getLaunchIntentForPackage(pkg)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onSuccess {
                Thread {
                    runCatching {
                        com.fran.teclas.predict.Predictor.recordLaunch(
                            this, pkg, com.fran.teclas.predict.LaunchSource.OTHER)
                    }
                }.start()
            }
            .onFailure { Toast.makeText(this, "Can't open ${item.label}", Toast.LENGTH_SHORT).show() }
    }

    override fun openFolder(item: GridItem) = showFolderDialog(item)

    override fun itemsChanged(items: List<GridItem>) = GridWorkspaceStore.save(this, items)

    override fun widgetRemoved(widgetId: Int) {
        runCatching { appWidgetHost.deleteAppWidgetId(widgetId) }
    }

    override fun widgetResized(item: GridItem, widthPx: Int, heightPx: Int) {
        val dm = resources.displayMetrics
        val wDp = (widthPx / dm.density).roundToInt()
        val hDp = (heightPx / dm.density).roundToInt()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
        widgetIdsOf(item).forEach { id ->
            runCatching { appWidgetManager.updateAppWidgetOptions(id, options) }
        }
    }

    override fun addRequested() {
        val labels = arrayOf("ADD APP", "ADD WIDGET")
        AlertDialog.Builder(this)
            .setItems(labels) { _, which -> if (which == 0) openDrawer() else showWidgetPicker() }
            .show()
    }

    override fun loadIcon(packageName: String?, className: String?): Drawable? =
        GridIcons.resolve(this, packageName, className)

    override fun createWidgetView(widgetId: Int): View? {
        val info = appWidgetManager.getAppWidgetInfo(widgetId) ?: return null
        return runCatching {
            appWidgetHost.createView(this, widgetId, info).apply {
                setAppWidget(widgetId, info)
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------ widget picker

    private fun showWidgetPicker() {
        val providers = appWidgetManager.installedProviders
            .sortedBy { it.loadLabel(packageManager).lowercase() }
        val panel = pickerPanel("ADD WIDGET")
        val list = panel.second
        val dialog = pickerDialog(panel.first)
        for (provider in providers) {
            list.addView(pickerRow(
                label = provider.loadLabel(packageManager),
                icon = runCatching { provider.loadPreviewImage(this, resources.displayMetrics.densityDpi) }.getOrNull()
                    ?: runCatching { packageManager.getApplicationIcon(provider.provider.packageName) }.getOrNull()
            ) {
                dialog.dismiss()
                bindWidget(provider)
            })
        }
    }

    private fun bindWidget(provider: AppWidgetProviderInfo) {
        val widgetId = appWidgetHost.allocateAppWidgetId()
        pendingWidgetId = widgetId
        pendingProvider = provider
        val bound = runCatching { appWidgetManager.bindAppWidgetIdIfAllowed(widgetId, provider.provider) }.getOrDefault(false)
        if (bound) {
            configureOrPlaceWidget(widgetId, provider)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            }
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(intent, REQUEST_BIND) }.onFailure {
                appWidgetHost.deleteAppWidgetId(widgetId)
                Toast.makeText(this, "Widget permission is needed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureOrPlaceWidget(widgetId: Int, provider: AppWidgetProviderInfo) {
        if (provider.configure != null) {
            runCatching {
                startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = provider.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }, REQUEST_CONFIGURE)
            }.onFailure { placeWidget(widgetId, provider) }
        } else {
            placeWidget(widgetId, provider)
        }
    }

    private fun placeWidget(widgetId: Int, provider: AppWidgetProviderInfo) {
        val cellWpx = resources.displayMetrics.widthPixels / GRID_COLS.toFloat()
        val cellHpx = resources.displayMetrics.heightPixels / (GRID_ROWS * 1.6f)
        val spanX = ceil(provider.minWidth / cellWpx).toInt().coerceIn(1, GRID_COLS)
        val spanY = ceil(provider.minHeight / cellHpx).toInt().coerceIn(1, GRID_ROWS)
        val cell = firstFreeCell(grid.currentItems(), spanX, spanY)
            ?: firstFreeCell(grid.currentItems(), 1, 1)
        if (cell == null) {
            Toast.makeText(this, "Grid is full", Toast.LENGTH_SHORT).show()
            appWidgetHost.deleteAppWidgetId(widgetId)
            return
        }
        val fitX = minOf(spanX, GRID_COLS - cell.first)
        val fitY = minOf(spanY, GRID_ROWS - cell.second)
        val item = GridItem(
            id = "widget-$widgetId",
            type = GridItemType.WIDGET,
            cellX = cell.first, cellY = cell.second,
            spanX = fitX, spanY = fitY,
            label = provider.loadLabel(packageManager),
            widgetId = widgetId,
        )
        val items = grid.currentItems() + item
        GridWorkspaceStore.save(this, items)
        grid.setItems(items)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
        when (requestCode) {
            REQUEST_BIND -> {
                val provider = pendingProvider
                if (resultCode == RESULT_OK && provider != null && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    configureOrPlaceWidget(widgetId, provider)
                } else if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    runCatching { appWidgetHost.deleteAppWidgetId(widgetId) }
                }
            }
            REQUEST_CONFIGURE -> {
                val provider = pendingProvider
                if (resultCode == RESULT_OK && provider != null && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    placeWidget(widgetId, provider)
                } else if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    runCatching { appWidgetHost.deleteAppWidgetId(widgetId) }
                }
            }
        }
        if (requestCode == REQUEST_BIND || requestCode == REQUEST_CONFIGURE) {
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            pendingProvider = null
        }
    }

    // -------------------------------------------------------------- folder UI

    private fun showFolderDialog(folder: GridItem) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(panelBg)
        }
        val nameEdit = EditText(this).apply {
            setText(folder.folderName ?: "Folder")
            setTextColor(ink)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            backgroundTintList = ColorStateList.valueOf(accent)
        }
        content.addView(nameEdit)
        content.addView(mono("TAP TO OPEN · LONG-PRESS TO PULL OUT", 8.5f, inkDim).apply {
            letterSpacing = 0.14f; setPadding(0, dp(6), 0, dp(10))
        })

        lateinit var dialog: AlertDialog
        val listWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (child in folder.children) {
            listWrap.addView(pickerRow(child.label ?: child.packageName ?: "?", loadIcon(child.packageName, child.className), onLongClick = {
                val cell = firstFreeCell(grid.currentItems(), 1, 1)
                if (cell == null) {
                    Toast.makeText(this, "Grid is full", Toast.LENGTH_SHORT).show()
                    return@pickerRow
                }
                val remaining = folder.children.filter { it.id != child.id }
                var items = grid.currentItems().filter { it.id != folder.id }
                items = items + child.copy(cellX = cell.first, cellY = cell.second)
                items = when {
                    remaining.size > 1 -> items + folder.copy(children = remaining)
                    remaining.size == 1 -> items + remaining[0].copy(cellX = folder.cellX, cellY = folder.cellY)
                    else -> items
                }
                GridWorkspaceStore.save(this, items)
                grid.setItems(items)
                dialog.dismiss()
            }) {
                dialog.dismiss()
                launchApp(child)
            })
        }
        content.addView(ScrollView(this).apply { addView(listWrap) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))

        dialog = AlertDialog.Builder(this).setView(content).create()
        dialog.setOnDismissListener {
            val newName = nameEdit.text.toString().trim().ifEmpty { "Folder" }
            if (newName != folder.folderName) {
                val items = grid.currentItems().map {
                    if (it.id == folder.id) it.copy(folderName = newName) else it
                }
                GridWorkspaceStore.save(this, items)
                grid.setItems(items)
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------ picker chrome

    private fun pickerPanel(title: String): Pair<View, LinearLayout> {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelBg)
            setPadding(dp(20), dp(16), dp(20), dp(10))
            addView(mono(title, 10f, accent).apply { letterSpacing = 0.22f; setPadding(0, 0, 0, dp(10)) })
            addView(ScrollView(context).apply { addView(list) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
        }
        return root to list
    }

    private fun pickerDialog(view: View): AlertDialog =
        AlertDialog.Builder(this).setView(view).create().also { it.show() }

    private fun pickerRow(label: String, icon: Drawable?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(9), dp(4), dp(9))
            addView(ImageView(context).apply {
                setImageDrawable(icon)
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(14) })
            addView(mono(label, 11.5f, ink).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener { onLongClick(); true }
            }
        }
    }

    companion object {
        // Distinct from MainActivity's WIDGET_HOST_ID (1407) — the grid must never collide with
        // the shipping launcher's hosted widget ids.
        private const val WIDGET_HOST_ID = 2607
        private const val REQUEST_BIND = 71
        private const val REQUEST_CONFIGURE = 72
    }
}
