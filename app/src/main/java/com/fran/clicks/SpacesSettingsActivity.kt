package com.fran.clicks

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.fran.clicks.predict.Place
import com.fran.clicks.predict.PlaceInference
import com.fran.clicks.predict.PlaceKind
import com.fran.clicks.predict.PlaceStore
import com.fran.clicks.predict.PlaceSuggestionNotifier
import com.fran.clicks.predict.Predictor
import com.fran.clicks.predict.Space
import com.fran.clicks.predict.SpaceManager
import com.fran.clicks.predict.SpaceTriggers
import java.util.Locale

/**
 * Spaces settings — fine-tune how the launcher rearranges itself around context.
 * Follows the ImeSettingsActivity pattern: programmatic Neu-styled views, shared
 * "clicks" prefs. Two modes in one activity: the Space list (with My Places and the
 * AI toggle) and a per-Space editor (rename, triggers, pinned/excluded apps, reset).
 */
class SpacesSettingsActivity : Activity() {

    private val accent = 0xFF8B5CF6.toInt()
    private lateinit var t: NeuTokens
    private var contentRoot: LinearLayout? = null
    private var editingSpaceId: String? = null
    @Volatile private var installedApps: List<Pair<String, String>> = emptyList() // label -> package

    private companion object {
        private const val PREFS_NAME = "clicks"
        private const val REQ_BLUETOOTH = 41
        private const val REQ_LOCATION = 42
        private const val REQ_NOTIFICATIONS = 43
        private val HOUR_LABELS = listOf("NIGHT", "DAWN", "AM", "MIDDAY", "PM", "EVE")
        private val KIND_LABELS = mapOf(
            PlaceKind.HOME to "Home", PlaceKind.WORK to "Work", PlaceKind.GYM to "Gym",
            PlaceKind.AIRPORT to "Airport", PlaceKind.OTHER to "Other",
        )
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tokens(): NeuTokens = when (prefs().getString("theme_mode", "system")) {
        "dark" -> Neu.Dark
        "light" -> Neu.Light
        else -> {
            val night = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) Neu.Dark else Neu.Light
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = tokens()
        window.setBackgroundDrawable(ColorDrawable(t.base))
        window.statusBarColor = t.base
        if (t.mode == NeuMode.LIGHT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            installedApps = pm.queryIntentActivities(launcher, 0)
                .asSequence()
                .filter { it.activityInfo.packageName != packageName }
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase(Locale.US) }
                .toList()
        }.start()
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (editingSpaceId != null) {
            editingSpaceId = null
            render()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        contentRoot = root
        val editing = editingSpaceId?.let { SpaceManager.space(this, it) }
        if (editing == null) renderList(root) else renderEditor(root, editing)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    // ---- list mode ------------------------------------------------------------------------

    private fun renderList(root: LinearLayout) {
        root.addView(text("Spaces", 23f, t.ink, bold = true))
        root.addView(text("The drawer, dock and search rearrange around where you are and what you're doing.", 13.5f, t.inkDim).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(2)
        })

        // Active now
        section("ACTIVE NOW").also { c ->
            val active = text("Detecting…", 15.5f, t.ink)
            val detail = text("", 12.5f, t.inkDim)
            c.addView(active)
            c.addView(detail.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(2) })
            Thread {
                val result = runCatching {
                    val snap = Predictor.snapshotNow(this)
                    SpaceManager.detect(this, snap) to snap
                }.getOrNull()
                runOnUiThread {
                    if (result == null) { active.text = "Not available"; return@runOnUiThread }
                    val (det, snap) = result
                    active.text = "${det.space.emoji}  ${det.space.name}" + if (det.locked) "  ·  locked" else ""
                    val bits = buildList {
                        add(HOUR_LABELS[snap.hourBucket].lowercase(Locale.US))
                        if (snap.isWeekend) add("weekend")
                        PlaceStore.places(this@SpacesSettingsActivity).find { it.id == snap.placeId }
                            ?.let { add("at ${it.name}") }
                        if (snap.driving) add("driving")
                        if (snap.headphones) add("headphones")
                        if (snap.mediaPlaying) add("music playing")
                        if (snap.charging) add("charging")
                    }
                    detail.text = "Signals: " + bits.joinToString(" · ")
                }
            }.start()
        }

        // Suggested places — the same one-tap confirms the notification offers.
        val suggestions = PlaceInference.pending(this)
        if (suggestions.isNotEmpty()) {
            section("SUGGESTED PLACES").also { c ->
                suggestions.forEachIndexed { i, s ->
                    if (i > 0) c.addView(divider())
                    c.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(9), 0, dp(9))
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text("Is this ${PlaceInference.defaultName(s.kind).lowercase(Locale.US)}?", 15f, t.ink))
                            addView(text(s.reason, 12f, t.inkDim).apply {
                                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
                            })
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(smallAction("Yes", enabled = true) {
                            val place = PlaceInference.accept(this@SpacesSettingsActivity, s.key)
                            PlaceSuggestionNotifier.cancel(this@SpacesSettingsActivity, s.key)
                            if (place != null) {
                                Toast.makeText(this@SpacesSettingsActivity, "${place.name} saved", Toast.LENGTH_SHORT).show()
                            }
                            render()
                        })
                        addView(smallAction("No", enabled = true) {
                            PlaceInference.dismiss(this@SpacesSettingsActivity, s.key)
                            PlaceSuggestionNotifier.cancel(this@SpacesSettingsActivity, s.key)
                            render()
                        })
                    })
                }
            }
        }

        // Spaces
        section("SPACES").also { c ->
            val lockedId = SpaceManager.lockedSpaceId(this)
            SpaceManager.spaces(this).forEachIndexed { i, space ->
                if (i > 0) c.addView(divider())
                c.addView(spaceRow(space, locked = space.id == lockedId))
            }
        }

        // My places — manual locations (home, gym, airports…)
        section("MY PLACES").also { c ->
            c.addView(text(
                "Name the places that matter — home, the gym, an airport — and Spaces can key off them. " +
                    "Saved encrypted on this phone only.", 12.5f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
            })
            val places = PlaceStore.places(this)
            if (places.isEmpty()) {
                c.addView(text("No places yet.", 13.5f, t.inkFaint).apply {
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)
                })
            }
            places.forEachIndexed { i, place ->
                if (i > 0) c.addView(divider())
                c.addView(placeRow(place))
            }
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)
                addView(button("Use current location") { addPlaceFromCurrentLocation() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(button("Add by address", filled = false) { addPlaceByAddress() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
            c.addView(divider().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12) })
            c.addView(switchRow(
                "Suggest places automatically",
                "Notify when Clicks spots your home, work or an airport from your movement — decided on-device",
                prefs().getBoolean(PlaceInference.ENABLED_PREF, true),
            ) { on ->
                prefs().edit().putBoolean(PlaceInference.ENABLED_PREF, on).apply()
                if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                }
            })
        }

        // AI layer
        section("PREDICTION AI").also { c ->
            c.addView(toggleRow(
                "Gemini re-ranking", "Let Gemini re-order predictions using coarse context labels",
                SpaceManager.AI_LAYER_KEY, false))
            c.addView(text(
                "Privacy: all learning happens on this phone and is stored encrypted. Nothing leaves " +
                    "the device unless this AI layer is on — and even then only app names and coarse labels " +
                    "like “driving” are sent, never your location, calendar or history.",
                12f, t.inkDim).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) })
        }

        // Reset
        section("LEARNING").also { c ->
            c.addView(button("Reset all learning", filled = false) {
                AlertDialog.Builder(this)
                    .setTitle("Reset all learning?")
                    .setMessage("Clears every learned pattern, weight and the launch log. Your places, pins and Space settings stay.")
                    .setPositiveButton("Reset") { _, _ ->
                        Predictor.resetAllLearning(this)
                        Toast.makeText(this, "Learning reset", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun spaceRow(space: Space, locked: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        addView(text(space.emoji, 19f, t.ink).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(space.name + if (locked) "  ·  locked" else "", 15.5f, if (space.enabled) t.ink else t.inkFaint))
            addView(text(triggerSummary(space.triggers), 12f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text("›", 19f, t.inkFaint),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener { editingSpaceId = space.id; render() }
    }

    private fun triggerSummary(tr: SpaceTriggers): String {
        val bits = mutableListOf<String>()
        if (tr.hourBuckets.isNotEmpty()) {
            bits.add(tr.hourBuckets.sorted().joinToString("/") { HOUR_LABELS[it].lowercase(Locale.US) })
        }
        tr.weekdaysOnly?.let { bits.add(if (it) "weekdays" else "weekends") }
        if (tr.placeKinds.isNotEmpty()) bits.add(tr.placeKinds.joinToString("/") { KIND_LABELS[it] ?: it.name })
        if (tr.placeIds.isNotEmpty()) {
            val names = PlaceStore.places(this).filter { it.id in tr.placeIds }.map { it.name }
            if (names.isNotEmpty()) bits.add("at " + names.joinToString("/"))
        }
        if (tr.driving) bits.add("driving")
        if (tr.btCar) bits.add("car bluetooth")
        if (tr.headphones) bits.add("headphones")
        if (tr.calendarBusy) bits.add("in/near meetings")
        return if (bits.isEmpty()) "No triggers — manual only" else bits.joinToString(" · ")
    }

    private fun placeRow(place: Place): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(9), 0, dp(9))
        isClickable = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(place.name, 15f, t.ink))
            addView(text("${KIND_LABELS[place.kind] ?: place.kind.name} · within ${place.radiusM.toInt()} m", 12f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text("›", 19f, t.inkFaint),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener { showPlaceDialog(existing = place, lat = place.lat, lng = place.lng) }
    }

    // ---- add / edit places ----------------------------------------------------------------

    private fun addPlaceFromCurrentLocation() {
        if (!AgenticLocation.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            Toast.makeText(this, "Allow location, then try again", Toast.LENGTH_SHORT).show()
            return
        }
        val fix = AgenticLocation.lastKnown(this)
        if (fix == null) {
            Toast.makeText(this, "No location fix yet — open Maps once and retry", Toast.LENGTH_LONG).show()
            return
        }
        showPlaceDialog(existing = null, lat = fix.latitude, lng = fix.longitude)
    }

    private fun addPlaceByAddress() {
        val input = EditText(this).apply {
            hint = "Address or place, e.g. “SFO airport”"
            setHintTextColor(t.inkFaint); setTextColor(t.ink)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Find a place")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isEmpty()) return@setPositiveButton
                Thread {
                    val hits = runCatching {
                        @Suppress("DEPRECATION")
                        Geocoder(this, Locale.getDefault()).getFromLocationName(query, 5)
                    }.getOrNull().orEmpty()
                    runOnUiThread {
                        if (hits.isEmpty()) {
                            Toast.makeText(this, "No match for “$query”", Toast.LENGTH_SHORT).show()
                        } else {
                            val labels = hits.map { it.getAddressLine(0) ?: "${it.latitude}, ${it.longitude}" }
                            AlertDialog.Builder(this)
                                .setTitle("Which one?")
                                .setItems(labels.toTypedArray()) { _, which ->
                                    val a = hits[which]
                                    showPlaceDialog(existing = null, lat = a.latitude, lng = a.longitude, suggestedName = query)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Shared editor for a new or existing place: name, kind, radius, delete. */
    private fun showPlaceDialog(existing: Place?, lat: Double, lng: Double, suggestedName: String? = null) {
        var kind = existing?.kind ?: PlaceKind.OTHER
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val nameInput = EditText(this).apply {
            setText(existing?.name ?: suggestedName ?: "")
            hint = "Name (Home, Gym, SFO…)"
            setHintTextColor(t.inkFaint); setTextColor(t.ink)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        column.addView(nameInput)
        val kindRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var repaintKinds: () -> Unit
        repaintKinds = {
            kindRow.removeAllViews()
            KIND_LABELS.forEach { (k, label) ->
                val on = k == kind
                kindRow.addView(TextView(this).apply {
                    text = label.uppercase(Locale.US)
                    gravity = Gravity.CENTER; textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(if (on) t.ink else t.inkDim)
                    background = Neu.drawable(t, dp(10).toFloat(), if (on) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    setOnClickListener {
                        kind = k
                        // Airports are big; nudge the radius when the kind changes on a new place.
                        repaintKinds()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (kindRow.childCount > 0) marginStart = dp(6)
                })
            }
        }
        repaintKinds()
        column.addView(kindRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        val radiusLabel = TextView(this).apply {
            textSize = 12.5f; setTextColor(t.inkDim)
        }
        var radius = existing?.radiusM?.toInt() ?: 250
        radiusLabel.text = "Radius: $radius m"
        val radiusBar = SeekBar(this).apply {
            max = 100
            progress = ((radius - 100) / 29).coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    radius = 100 + value * 29 // 100 m .. ~3 km (airports)
                    radiusLabel.text = "Radius: $radius m"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        column.addView(radiusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        column.addView(radiusBar)
        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Save place" else "Edit place")
            .setView(column)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (existing == null) {
                    PlaceStore.addPlace(this, name, kind, lat, lng, radius.toFloat())
                } else {
                    PlaceStore.updatePlace(this, existing.copy(name = name.ifEmpty { existing.name }, kind = kind, radiusM = radius.toFloat()))
                }
                render()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                PlaceStore.removePlace(this, existing.id)
                // Drop dangling references from Space triggers.
                SpaceManager.spaces(this).forEach { s ->
                    if (existing.id in s.triggers.placeIds) {
                        SpaceManager.update(this, s.copy(triggers = s.triggers.copy(placeIds = s.triggers.placeIds - existing.id)))
                    }
                }
                render()
            }
        }
        builder.show()
    }

    // ---- editor mode ------------------------------------------------------------------------

    private fun renderEditor(root: LinearLayout, spaceIn: Space) {
        var space = spaceIn
        fun commit(updated: Space) {
            space = updated
            SpaceManager.update(this, updated)
        }

        root.addView(text("‹ All Spaces", 14f, accent).apply {
            isClickable = true
            setOnClickListener { editingSpaceId = null; render() }
        })
        root.addView(text("${space.emoji}  ${space.name}", 23f, t.ink, bold = true).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })

        section("SPACE").also { c ->
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(2))
                addView(text("Name", 12.5f, t.inkDim))
                addView(EditText(context).apply {
                    setText(space.name)
                    setTextColor(t.ink); setHintTextColor(t.inkFaint)
                    textSize = 14.5f
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = Neu.drawable(t, dp(11).toFloat(), NeuLevel.PRESSED_SM)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    setSingleLine()
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            val name = s?.toString()?.trim().orEmpty()
                            if (name.isNotEmpty()) commit(space.copy(name = name))
                        }
                    })
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(5) }
                })
            })
            c.addView(switchRow("Enabled", "Off = this Space is never detected", space.enabled) {
                commit(space.copy(enabled = it))
            })
            c.addView(divider())
            c.addView(switchRow("Auto-switch", "Activate automatically when triggers match", space.autoSwitch) {
                commit(space.copy(autoSwitch = it))
            })
        }

        section("TRIGGERS").also { c ->
            c.addView(text("Time of day", 12.5f, t.inkDim))
            c.addView(hourChips(space.triggers.hourBuckets) { hours ->
                commit(space.copy(triggers = space.triggers.copy(hourBuckets = hours)))
            })
            c.addView(text("Days", 12.5f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            })
            c.addView(dayChips(space.triggers.weekdaysOnly) { days ->
                commit(space.copy(triggers = space.triggers.copy(weekdaysOnly = days)))
            })
            c.addView(text("Places", 12.5f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            })
            c.addView(placeChips(space) { kinds, ids ->
                commit(space.copy(triggers = space.triggers.copy(placeKinds = kinds, placeIds = ids)))
            })
            c.addView(divider().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
            c.addView(switchRow("Driving", "Moving at driving speed, or car bluetooth", space.triggers.driving) {
                commit(space.copy(triggers = space.triggers.copy(driving = it)))
            })
            c.addView(switchRow("Car bluetooth", "Connected to a car kit", space.triggers.btCar) { on ->
                if (on && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
                }
                commit(space.copy(triggers = space.triggers.copy(btCar = on)))
            })
            c.addView(switchRow("Headphones", "Wired or bluetooth audio connected", space.triggers.headphones) {
                commit(space.copy(triggers = space.triggers.copy(headphones = it)))
            })
            c.addView(switchRow("Meetings", "In a calendar event, or one starts within 30 min", space.triggers.calendarBusy) {
                commit(space.copy(triggers = space.triggers.copy(calendarBusy = it)))
            })
            c.addView(text(
                "All chosen triggers must match; a Space with more matching triggers wins.",
                12f, t.inkFaint).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) })
        }

        section("PINNED APPS").also { c ->
            c.addView(text("Always lead this Space's predictions, in this order.", 12.5f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(4)
            })
            space.pinned.forEachIndexed { i, pkg ->
                c.addView(appEntryRow(
                    label = appLabel(pkg), showArrows = true,
                    onUp = if (i == 0) null else {
                        {
                            val list = space.pinned.toMutableList()
                            list[i] = list[i - 1].also { list[i - 1] = list[i] }
                            commit(space.copy(pinned = list)); render()
                        }
                    },
                    onDown = if (i == space.pinned.size - 1) null else {
                        {
                            val list = space.pinned.toMutableList()
                            list[i] = list[i + 1].also { list[i + 1] = list[i] }
                            commit(space.copy(pinned = list)); render()
                        }
                    },
                    onRemove = { commit(space.copy(pinned = space.pinned - pkg)); render() },
                ))
            }
            c.addView(button("Pin an app", filled = false) {
                pickApp(exclude = space.pinned.toSet()) { pkg ->
                    commit(space.copy(pinned = space.pinned + pkg)); render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        section("EXCLUDED APPS").also { c ->
            c.addView(text("Never predicted in this Space.", 12.5f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(4)
            })
            space.excluded.forEach { pkg ->
                c.addView(appEntryRow(appLabel(pkg), showArrows = false, onUp = null, onDown = null,
                    onRemove = { commit(space.copy(excluded = space.excluded - pkg)); render() }))
            }
            c.addView(button("Exclude an app", filled = false) {
                pickApp(exclude = space.excluded.toSet()) { pkg ->
                    commit(space.copy(excluded = space.excluded + pkg)); render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        section("LEARNING").also { c ->
            c.addView(button("Reset learning for ${space.name}", filled = false) {
                AlertDialog.Builder(this)
                    .setTitle("Reset ${space.name}?")
                    .setMessage("Forgets which apps were learned for this Space. Pins, exclusions and triggers stay.")
                    .setPositiveButton("Reset") { _, _ ->
                        Predictor.resetSpaceLearning(this, space.id)
                        Toast.makeText(this, "${space.name} learning reset", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---- chips ------------------------------------------------------------------------------

    private fun hourChips(selected: Set<Int>, onChange: (Set<Int>) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var current = selected
        lateinit var repaint: () -> Unit
        repaint = {
            row.removeAllViews()
            HOUR_LABELS.forEachIndexed { i, label ->
                val on = i in current
                row.addView(chip(label, on) {
                    current = if (on) current - i else current + i
                    onChange(current); repaint()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                })
            }
        }
        repaint()
        return row.withTopMargin(dp(6))
    }

    private fun dayChips(selected: Boolean?, onChange: (Boolean?) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var current = selected
        lateinit var repaint: () -> Unit
        val options = listOf<Pair<String, Boolean?>>("ANY" to null, "WEEKDAYS" to true, "WEEKENDS" to false)
        repaint = {
            row.removeAllViews()
            options.forEachIndexed { i, (label, value) ->
                row.addView(chip(label, current == value) {
                    current = value
                    onChange(value); repaint()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                })
            }
        }
        repaint()
        return row.withTopMargin(dp(6))
    }

    private fun placeChips(space: Space, onChange: (Set<PlaceKind>, Set<String>) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var kinds = space.triggers.placeKinds
        var ids = space.triggers.placeIds
        val places = PlaceStore.places(this)
        lateinit var repaint: () -> Unit
        repaint = {
            row.removeAllViews()
            KIND_LABELS.forEach { (k, label) ->
                val on = k in kinds
                row.addView(chip(label.uppercase(Locale.US), on) {
                    kinds = if (on) kinds - k else kinds + k
                    onChange(kinds, ids); repaint()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (row.childCount > 0) marginStart = dp(6)
                })
            }
            places.forEach { place ->
                val on = place.id in ids
                row.addView(chip("@ ${place.name.uppercase(Locale.US)}", on) {
                    ids = if (on) ids - place.id else ids + place.id
                    onChange(kinds, ids); repaint()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(6)
                })
            }
        }
        repaint()
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }.withTopMargin(dp(6))
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 10.5f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.04f
        setTextColor(if (on) t.ink else t.inkDim)
        background = Neu.drawable(t, dp(10).toFloat(), if (on) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun View.withTopMargin(margin: Int): View = apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = margin }
    }

    // ---- app rows / picker --------------------------------------------------------------------

    private fun appLabel(pkg: String): String =
        installedApps.find { it.second == pkg }?.first
            ?: runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)

    private fun appEntryRow(
        label: String,
        showArrows: Boolean,
        onUp: (() -> Unit)?,
        onDown: (() -> Unit)?,
        onRemove: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(text(label, 14.5f, t.ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (showArrows) {
            addView(smallAction("▲", enabled = onUp != null) { onUp?.invoke() })
            addView(smallAction("▼", enabled = onDown != null) { onDown?.invoke() })
        }
        addView(smallAction("✕", enabled = true) { onRemove() })
    }

    private fun smallAction(glyph: String, enabled: Boolean, onClick: () -> Unit): View = TextView(this).apply {
        text = glyph
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(if (enabled) t.inkDim else (t.inkFaint and 0x00FFFFFF) or 0x40000000)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    private fun pickApp(exclude: Set<String>, onPicked: (String) -> Unit) {
        val options = installedApps.filter { it.second !in exclude }
        if (options.isEmpty()) {
            Toast.makeText(this, "Still loading apps — try again", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose an app")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> onPicked(options[which].second) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- shared builders (ImeSettingsActivity pattern) ---------------------------------------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun section(title: String): LinearLayout {
        val root = contentRoot!!
        root.addView(TextView(this).apply {
            text = title
            textSize = 10.5f
            letterSpacing = 0.14f
            setTextColor(accent)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22); bottomMargin = dp(8); leftMargin = dp(4) }
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Neu.drawable(t, dp(18).toFloat(), NeuLevel.RAISED_SM)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(card)
        return card
    }

    /** Switch row writing straight to a boolean pref. */
    private fun toggleRow(title: String, sub: String, pref: String, default: Boolean): View =
        switchRow(title, sub, prefs().getBoolean(pref, default)) { checked ->
            prefs().edit().putBoolean(pref, checked).apply()
        }

    /** Switch row with a callback (for Space fields that live in SpaceManager JSON). */
    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15.5f, t.ink))
                addView(text(sub, 12.5f, t.inkDim).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(context).apply {
                isChecked = checked
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
                thumbTintList = ColorStateList(states, intArrayOf(accent, t.inkDim))
                trackTintList = ColorStateList(states, intArrayOf(
                    (accent and 0x00FFFFFF) or 0x66000000, (t.inkDim and 0x00FFFFFF) or 0x40000000))
                setOnCheckedChangeListener { _, on -> onChange(on) }
            })
        }
    }

    private fun button(label: String, filled: Boolean = true, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(if (filled) 0xFFF5F2FF.toInt() else accent)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(8), dp(11), dp(8), dp(11))
        isClickable = true
        background = GradientDrawable().apply {
            if (filled) setColor(accent) else {
                setColor(0x00000000)
                setStroke(dp(1), (accent and 0x00FFFFFF) or 0x88000000.toInt())
            }
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor((t.inkFaint and 0x00FFFFFF) or 0x22000000)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }
}
