package com.fran.teclas

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fran.teclas.predict.Place
import com.fran.teclas.predict.PlaceInference
import com.fran.teclas.predict.PlaceKind
import com.fran.teclas.predict.PlaceStore
import com.fran.teclas.predict.PlaceSuggestionNotifier
import com.fran.teclas.predict.Predictor
import com.fran.teclas.predict.Space
import com.fran.teclas.predict.SpaceManager
import com.fran.teclas.predict.SpaceTriggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private data class PlaceDialogState(val existing: Place?, val lat: Double, val lng: Double, val suggestedName: String? = null)
private data class ConfirmState(val title: String, val message: String, val onConfirm: () -> Unit)
private data class PickAppState(val exclude: Set<String>, val onPicked: (String) -> Unit)
private data class AddressResults(val query: String, val hits: List<Address>)

private fun List<String>.swapped(a: Int, b: Int): List<String> =
    toMutableList().also { l -> l[a] = l[b]; l[b] = this[a] }

/**
 * Spaces settings — fine-tune how the launcher rearranges itself around context.
 * Compose (Material3) over Neu-styled surfaces, shared "teclas" prefs. Two modes in one
 * activity: the Space list (with My Places and the AI toggle) and a per-Space editor
 * (rename, triggers, pinned/excluded apps, reset).
 */
class SpacesSettingsActivity : ComponentActivity() {

    private lateinit var t: NeuTokens
    private var installedApps by mutableStateOf<List<Pair<String, String>>>(emptyList()) // label -> package

    // UI state (activity-scoped Compose state keeps the tree shallow).
    private var editingSpaceId by mutableStateOf<String?>(null)
    private var tick by mutableIntStateOf(0)
    private var placeDialog by mutableStateOf<PlaceDialogState?>(null)
    private var confirm by mutableStateOf<ConfirmState?>(null)
    private var pickApp by mutableStateOf<PickAppState?>(null)
    private var addressSearch by mutableStateOf(false)
    private var addressResults by mutableStateOf<AddressResults?>(null)

    private companion object {
        private const val PREFS_NAME = "teclas"
        private val ACCENT = Color(0xFF8B5CF6)
        private val HOUR_LABELS = listOf("NIGHT", "DAWN", "AM", "MIDDAY", "PM", "EVE")
        private val KIND_LABELS = mapOf(
            PlaceKind.HOME to "Home", PlaceKind.WORK to "Work", PlaceKind.GYM to "Gym",
            PlaceKind.AIRPORT to "Airport", PlaceKind.OTHER to "Other",
        )
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun refresh() { tick++ }
    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun tokens(): NeuTokens = when (prefs().getString("theme_mode", "system")) {
        "dark" -> Neu.Dark
        "light" -> Neu.Light
        else -> {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) Neu.Dark else Neu.Light
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
            val apps = pm.queryIntentActivities(launcher, 0)
                .asSequence()
                .filter { it.activityInfo.packageName != packageName }
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase(Locale.US) }
                .toList()
            runOnUiThread { installedApps = apps }
        }.start()
        setContent { SpacesScreen() }
    }

    @Composable
    private fun SpacesScreen() {
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val requestPerm: (String) -> Unit = { permLauncher.launch(it) }
        BackHandler(enabled = editingSpaceId != null) { editingSpaceId = null }
        val scroll = remember(editingSpaceId) { ScrollState(0) }
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll)
                .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 40.dp)
        ) {
            val editing = editingSpaceId?.let { SpaceManager.space(this@SpacesSettingsActivity, it) }
            if (editing == null) ListScreen(requestPerm) else EditorScreen(editing, requestPerm)
        }
        placeDialog?.let { PlaceDialog(it) }
        confirm?.let { ConfirmDialog(it) }
        pickApp?.let { PickAppDialog(it) }
        if (addressSearch) AddressSearchDialog()
        addressResults?.let { AddressResultsDialog(it) }
    }

    // ---- list mode ------------------------------------------------------------------------

    @Composable
    private fun ListScreen(requestPerm: (String) -> Unit) {
        val suggestions = remember(tick) { PlaceInference.pending(this) }
        val spaces = remember(tick) { SpaceManager.spaces(this) }
        val places = remember(tick) { PlaceStore.places(this) }
        val lockedId = remember(tick) { SpaceManager.lockedSpaceId(this) }

        Label("Spaces", 23.sp, t.inkCompose, bold = true)
        Label("The drawer, dock and search rearrange around where you are and what you're doing.",
            13.5.sp, t.inkDimCompose, topPad = 2.dp)

        Section("ACTIVE NOW") {
            var active by remember(tick) { mutableStateOf("Detecting…") }
            var detail by remember(tick) { mutableStateOf("") }
            LaunchedEffect(tick) {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val snap = Predictor.snapshotNow(this@SpacesSettingsActivity)
                        SpaceManager.detect(this@SpacesSettingsActivity, snap) to snap
                    }.getOrNull()
                }
                if (result == null) { active = "Not available"; return@LaunchedEffect }
                val (det, snap) = result
                active = "${det.space.emoji}  ${det.space.name}" + if (det.locked) "  ·  locked" else ""
                val bits = buildList {
                    add(HOUR_LABELS[snap.hourBucket].lowercase(Locale.US))
                    if (snap.isWeekend) add("weekend")
                    PlaceStore.places(this@SpacesSettingsActivity).find { it.id == snap.placeId }
                        ?.let { add("at ${it.name}") }
                    if (snap.driving) add("driving")
                    if (snap.headphones) add("headphones")
                    if (snap.mediaPlaying) add("music playing")
                    if (snap.charging) add("charging")
                    if (snap.awayFromHome) add("away from home")
                }
                detail = "Signals: " + bits.joinToString(" · ")
            }
            Label(active, 15.5.sp, t.inkCompose)
            Label(detail, 12.5.sp, t.inkDimCompose, topPad = 2.dp)
        }

        // Suggested places — the same one-tap confirms the notification offers.
        if (suggestions.isNotEmpty()) Section("SUGGESTED PLACES") {
            suggestions.forEachIndexed { i, s ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Is this ${PlaceInference.defaultName(s.kind).lowercase(Locale.US)}?", 15.sp, t.inkCompose)
                        Label(s.reason, 12.sp, t.inkDimCompose, topPad = 1.dp)
                    }
                    SmallAction("Yes") {
                        val place = PlaceInference.accept(this@SpacesSettingsActivity, s.key)
                        PlaceSuggestionNotifier.cancel(this@SpacesSettingsActivity, s.key)
                        if (place != null) toast("${place.name} saved")
                        refresh()
                    }
                    SmallAction("No") {
                        PlaceInference.dismiss(this@SpacesSettingsActivity, s.key)
                        PlaceSuggestionNotifier.cancel(this@SpacesSettingsActivity, s.key)
                        refresh()
                    }
                }
            }
        }

        Section("SPACES") {
            spaces.forEachIndexed { i, space ->
                if (i > 0) Hairline()
                Row(
                    Modifier.fillMaxWidth().clickable { editingSpaceId = space.id }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(space.emoji, Modifier.width(34.dp), color = t.inkCompose, fontSize = 19.sp)
                    Column(Modifier.weight(1f)) {
                        Label(space.name + if (space.id == lockedId) "  ·  locked" else "",
                            15.5.sp, if (space.enabled) t.inkCompose else t.inkFaintCompose)
                        Label(triggerSummary(space.triggers), 12.sp, t.inkDimCompose, topPad = 1.dp)
                    }
                    Text("›", color = t.inkFaintCompose, fontSize = 19.sp)
                }
            }
        }

        // My places — manual locations (home, gym, airports…)
        Section("MY PLACES") {
            Label("Name the places that matter — home, the gym, an airport — and Spaces can key off them. " +
                "Saved encrypted on this phone only.", 12.5.sp, t.inkDimCompose, bottomPad = 8.dp)
            if (places.isEmpty()) Label("No places yet.", 13.5.sp, t.inkFaintCompose, bottomPad = 6.dp)
            places.forEachIndexed { i, place ->
                if (i > 0) Hairline()
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { placeDialog = PlaceDialogState(place, place.lat, place.lng) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(place.name, 15.sp, t.inkCompose)
                        Label("${KIND_LABELS[place.kind] ?: place.kind.name} · within ${place.radiusM.toInt()} m",
                            12.sp, t.inkDimCompose, topPad = 1.dp)
                    }
                    Text("›", color = t.inkFaintCompose, fontSize = 19.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                NeuButton("Use current location", Modifier.weight(1f)) { addPlaceFromCurrentLocation(requestPerm) }
                Spacer(Modifier.width(8.dp))
                NeuButton("Add by address", Modifier.weight(1f), filled = false) { addressSearch = true }
            }
            Hairline(topPad = 12.dp)
            var suggestOn by remember { mutableStateOf(prefs().getBoolean(PlaceInference.ENABLED_PREF, true)) }
            SwitchRow(
                "Suggest places automatically",
                "Notify when Teclas spots your home, work or an airport from your movement — decided on-device",
                suggestOn,
            ) { on ->
                suggestOn = on
                prefs().edit().putBoolean(PlaceInference.ENABLED_PREF, on).apply()
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestPerm(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        Section("PREDICTION AI") {
            var aiOn by remember { mutableStateOf(prefs().getBoolean(SpaceManager.AI_LAYER_KEY, false)) }
            SwitchRow("Gemini re-ranking", "Let Gemini re-order predictions using coarse context labels", aiOn) {
                aiOn = it
                prefs().edit().putBoolean(SpaceManager.AI_LAYER_KEY, it).apply()
            }
            Label("Privacy: all learning happens on this phone and is stored encrypted. Nothing leaves " +
                "the device unless this AI layer is on — and even then only app names and coarse labels " +
                "like “driving” are sent, never your location, calendar or history.",
                12.sp, t.inkDimCompose, topPad = 6.dp)
        }

        Section("LEARNING") {
            NeuButton("Reset all learning", Modifier.fillMaxWidth(), filled = false) {
                confirm = ConfirmState(
                    "Reset all learning?",
                    "Clears every learned pattern, weight and the launch log. Your places, pins and Space settings stay.",
                ) {
                    Predictor.resetAllLearning(this@SpacesSettingsActivity)
                    toast("Learning reset")
                }
            }
        }
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

    // ---- editor mode ------------------------------------------------------------------------

    @Composable
    private fun EditorScreen(initial: Space, requestPerm: (String) -> Unit) {
        var space by remember(initial.id) { mutableStateOf(initial) }
        fun commit(updated: Space) { space = updated; SpaceManager.update(this, updated) }
        fun triggers(tr: SpaceTriggers) = commit(space.copy(triggers = tr))

        Text("‹ All Spaces", Modifier.fillMaxWidth().clickable { editingSpaceId = null },
            color = ACCENT, fontSize = 14.sp)
        Label("${space.emoji}  ${space.name}", 23.sp, t.inkCompose, bold = true, topPad = 10.dp)

        Section("SPACE") {
            Column(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                Label("Name", 12.5.sp, t.inkDimCompose)
                var name by remember(space.id) { mutableStateOf(space.name) }
                NeuTextField(name, {
                    name = it
                    it.trim().takeIf(String::isNotEmpty)?.let { n -> commit(space.copy(name = n)) }
                }, hint = "", modifier = Modifier.padding(top = 5.dp))
            }
            SwitchRow("Enabled", "Off = this Space is never detected", space.enabled) {
                commit(space.copy(enabled = it))
            }
            Hairline()
            SwitchRow("Auto-switch", "Activate automatically when triggers match", space.autoSwitch) {
                commit(space.copy(autoSwitch = it))
            }
        }

        Section("TRIGGERS") {
            val tr = space.triggers
            Label("Time of day", 12.5.sp, t.inkDimCompose)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                HOUR_LABELS.forEachIndexed { i, label ->
                    if (i > 0) Spacer(Modifier.width(6.dp))
                    val on = i in tr.hourBuckets
                    Chip(label, on, Modifier.weight(1f)) {
                        triggers(tr.copy(hourBuckets = if (on) tr.hourBuckets - i else tr.hourBuckets + i))
                    }
                }
            }
            Label("Days", 12.5.sp, t.inkDimCompose, topPad = 10.dp)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                listOf<Pair<String, Boolean?>>("ANY" to null, "WEEKDAYS" to true, "WEEKENDS" to false)
                    .forEachIndexed { i, (label, value) ->
                        if (i > 0) Spacer(Modifier.width(6.dp))
                        Chip(label, tr.weekdaysOnly == value, Modifier.weight(1f)) {
                            triggers(tr.copy(weekdaysOnly = value))
                        }
                    }
            }
            Label("Places", 12.5.sp, t.inkDimCompose, topPad = 10.dp)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KIND_LABELS.forEach { (k, label) ->
                    val on = k in tr.placeKinds
                    Chip(label.uppercase(Locale.US), on) {
                        triggers(tr.copy(placeKinds = if (on) tr.placeKinds - k else tr.placeKinds + k))
                    }
                }
                PlaceStore.places(this@SpacesSettingsActivity).forEach { place ->
                    val on = place.id in tr.placeIds
                    Chip("@ ${place.name.uppercase(Locale.US)}", on) {
                        triggers(tr.copy(placeIds = if (on) tr.placeIds - place.id else tr.placeIds + place.id))
                    }
                }
            }
            Hairline(topPad = 10.dp)
            SwitchRow("Driving", "Moving at driving speed, or car bluetooth", tr.driving) {
                triggers(tr.copy(driving = it))
            }
            SwitchRow("Car bluetooth", "Connected to a car kit", tr.btCar) { on ->
                if (on && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPerm(Manifest.permission.BLUETOOTH_CONNECT)
                }
                triggers(tr.copy(btCar = on))
            }
            SwitchRow("Headphones", "Wired or bluetooth audio connected", tr.headphones) {
                triggers(tr.copy(headphones = it))
            }
            SwitchRow("Meetings", "In a calendar event, or one starts within 30 min", tr.calendarBusy) {
                triggers(tr.copy(calendarBusy = it))
            }
            Label("All chosen triggers must match; a Space with more matching triggers wins.",
                12.sp, t.inkFaintCompose, topPad = 6.dp)
        }

        Section("PINNED APPS") {
            Label("Always lead this Space's predictions, in this order.", 12.5.sp, t.inkDimCompose, bottomPad = 4.dp)
            space.pinned.forEachIndexed { i, pkg ->
                AppEntryRow(
                    label = appLabel(pkg), showArrows = true,
                    onUp = if (i == 0) null else { { commit(space.copy(pinned = space.pinned.swapped(i, i - 1))) } },
                    onDown = if (i == space.pinned.size - 1) null else {
                        { commit(space.copy(pinned = space.pinned.swapped(i, i + 1))) }
                    },
                    onRemove = { commit(space.copy(pinned = space.pinned - pkg)) },
                )
            }
            NeuButton("Pin an app", Modifier.fillMaxWidth().padding(top = 8.dp), filled = false) {
                openAppPicker(exclude = space.pinned.toSet()) { pkg -> commit(space.copy(pinned = space.pinned + pkg)) }
            }
        }

        Section("EXCLUDED APPS") {
            Label("Never predicted in this Space.", 12.5.sp, t.inkDimCompose, bottomPad = 4.dp)
            space.excluded.forEach { pkg ->
                AppEntryRow(appLabel(pkg), showArrows = false, onUp = null, onDown = null,
                    onRemove = { commit(space.copy(excluded = space.excluded - pkg)) })
            }
            NeuButton("Exclude an app", Modifier.fillMaxWidth().padding(top = 8.dp), filled = false) {
                openAppPicker(exclude = space.excluded.toSet()) { pkg -> commit(space.copy(excluded = space.excluded + pkg)) }
            }
        }

        Section("LEARNED FOR THIS SPACE") {
            val learned = remember(tick) {
                Predictor.spaceLearned(this@SpacesSettingsActivity, space.id, 10)
            }
            if (learned.isEmpty()) {
                Label(
                    "Nothing yet. Open apps while ${space.name} is active (or locked) and they " +
                        "appear here instantly — this is exactly what the drawer and dock rank from.",
                    12.5.sp, t.inkDimCompose,
                )
            } else {
                Label("Every launch recorded while ${space.name} was active. Freshest first.",
                    12.5.sp, t.inkDimCompose, bottomPad = 4.dp)
                learned.forEachIndexed { i, (pkg, count) ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(appLabel(pkg), Modifier.weight(1f), color = t.inkCompose, fontSize = 14.5.sp)
                        Text(if (count == 1) "1 open" else "$count opens", color = t.inkDimCompose, fontSize = 12.sp)
                    }
                }
            }
        }

        Section("LEARNING") {
            NeuButton("Reset learning for ${space.name}", Modifier.fillMaxWidth(), filled = false) {
                confirm = ConfirmState(
                    "Reset ${space.name}?",
                    "Forgets which apps were learned for this Space. Pins, exclusions and triggers stay.",
                ) {
                    Predictor.resetSpaceLearning(this@SpacesSettingsActivity, space.id)
                    toast("${space.name} learning reset")
                }
            }
        }
    }

    // ---- add / edit places ----------------------------------------------------------------

    private fun addPlaceFromCurrentLocation(requestPerm: (String) -> Unit) {
        if (!AgenticLocation.hasPermission(this)) {
            requestPerm(Manifest.permission.ACCESS_FINE_LOCATION)
            toast("Allow location, then try again")
            return
        }
        val fix = AgenticLocation.lastKnown(this)
        if (fix == null) {
            toast("No location fix yet — open Maps once and retry", long = true)
            return
        }
        placeDialog = PlaceDialogState(existing = null, lat = fix.latitude, lng = fix.longitude)
    }

    @Composable
    private fun AddressSearchDialog() {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addressSearch = false },
            containerColor = t.hiCompose,
            title = { Text("Find a place", color = t.inkCompose) },
            text = { NeuTextField(query, { query = it }, hint = "Address or place, e.g. “SFO airport”", capWords = false) },
            confirmButton = {
                TextButton({
                    addressSearch = false
                    val q = query.trim()
                    if (q.isEmpty()) return@TextButton
                    Thread {
                        val hits = runCatching {
                            @Suppress("DEPRECATION")
                            Geocoder(this, Locale.getDefault()).getFromLocationName(q, 5)
                        }.getOrNull().orEmpty()
                        runOnUiThread {
                            if (hits.isEmpty()) toast("No match for “$q”")
                            else addressResults = AddressResults(q, hits)
                        }
                    }.start()
                }) { Text("Search", color = ACCENT) }
            },
            dismissButton = { TextButton({ addressSearch = false }) { Text("Cancel", color = t.inkDimCompose) } },
        )
    }

    @Composable
    private fun AddressResultsDialog(r: AddressResults) = AlertDialog(
        onDismissRequest = { addressResults = null },
        containerColor = t.hiCompose,
        title = { Text("Which one?", color = t.inkCompose) },
        text = {
            Column {
                r.hits.forEach { a ->
                    Text(a.getAddressLine(0) ?: "${a.latitude}, ${a.longitude}",
                        Modifier.fillMaxWidth().clickable {
                            addressResults = null
                            placeDialog = PlaceDialogState(null, a.latitude, a.longitude, suggestedName = r.query)
                        }.padding(vertical = 10.dp),
                        color = t.inkCompose, fontSize = 14.5.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ addressResults = null }) { Text("Cancel", color = t.inkDimCompose) } },
    )

    /** Shared editor for a new or existing place: name, kind, radius, delete. */
    @Composable
    private fun PlaceDialog(d: PlaceDialogState) {
        val existing = d.existing
        var name by remember(d) { mutableStateOf(existing?.name ?: d.suggestedName ?: "") }
        var kind by remember(d) { mutableStateOf(existing?.kind ?: PlaceKind.OTHER) }
        var radius by remember(d) { mutableIntStateOf(existing?.radiusM?.toInt() ?: 250) }
        AlertDialog(
            onDismissRequest = { placeDialog = null },
            containerColor = t.hiCompose,
            title = { Text(if (existing == null) "Save place" else "Edit place", color = t.inkCompose) },
            text = {
                Column {
                    NeuTextField(name, { name = it }, hint = "Name (Home, Gym, SFO…)")
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        KIND_LABELS.entries.forEachIndexed { i, (k, label) ->
                            if (i > 0) Spacer(Modifier.width(6.dp))
                            Chip(label.uppercase(Locale.US), k == kind, Modifier.weight(1f),
                                textSize = 11.sp, hPad = 0.dp) { kind = k }
                        }
                    }
                    Text("Radius: $radius m", Modifier.padding(top = 12.dp), color = t.inkDimCompose, fontSize = 12.5.sp)
                    Slider(
                        value = ((radius - 100) / 29f).coerceIn(0f, 100f),
                        onValueChange = { radius = 100 + it.roundToInt() * 29 }, // 100 m .. ~3 km (airports)
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = ACCENT, activeTrackColor = ACCENT,
                            inactiveTrackColor = t.inkFaintCompose.copy(alpha = 0.25f)),
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val nm = name.trim()
                    if (existing == null) {
                        PlaceStore.addPlace(this@SpacesSettingsActivity, nm, kind, d.lat, d.lng, radius.toFloat())
                    } else {
                        PlaceStore.updatePlace(this@SpacesSettingsActivity, existing.copy(
                            name = nm.ifEmpty { existing.name }, kind = kind, radiusM = radius.toFloat()))
                    }
                    placeDialog = null
                    refresh()
                }) { Text("Save", color = ACCENT) }
            },
            dismissButton = {
                Row {
                    if (existing != null) TextButton({
                        PlaceStore.removePlace(this@SpacesSettingsActivity, existing.id)
                        // Drop dangling references from Space triggers.
                        SpaceManager.spaces(this@SpacesSettingsActivity).forEach { s ->
                            if (existing.id in s.triggers.placeIds) {
                                SpaceManager.update(this@SpacesSettingsActivity, s.copy(
                                    triggers = s.triggers.copy(placeIds = s.triggers.placeIds - existing.id)))
                            }
                        }
                        placeDialog = null
                        refresh()
                    }) { Text("Delete", color = t.inkDimCompose) }
                    TextButton({ placeDialog = null }) { Text("Cancel", color = t.inkDimCompose) }
                }
            },
        )
    }

    // ---- app rows / picker --------------------------------------------------------------------

    private fun appLabel(pkg: String): String =
        installedApps.find { it.second == pkg }?.first
            ?: runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)

    @Composable
    private fun AppEntryRow(label: String, showArrows: Boolean, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onRemove: () -> Unit) =
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = t.inkCompose, fontSize = 14.5.sp)
            if (showArrows) {
                SmallAction("▲", enabled = onUp != null) { onUp?.invoke() }
                SmallAction("▼", enabled = onDown != null) { onDown?.invoke() }
            }
            SmallAction("✕", onClick = onRemove)
        }

    private fun openAppPicker(exclude: Set<String>, onPicked: (String) -> Unit) {
        if (installedApps.none { it.second !in exclude }) {
            toast("Still loading apps — try again")
            return
        }
        pickApp = PickAppState(exclude, onPicked)
    }

    @Composable
    private fun PickAppDialog(p: PickAppState) = AlertDialog(
        onDismissRequest = { pickApp = null },
        containerColor = t.hiCompose,
        title = { Text("Choose an app", color = t.inkCompose) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(installedApps.filter { it.second !in p.exclude }) { (label, pkg) ->
                    Text(label,
                        Modifier.fillMaxWidth().clickable { pickApp = null; p.onPicked(pkg) }.padding(vertical = 10.dp),
                        color = t.inkCompose, fontSize = 14.5.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ pickApp = null }) { Text("Cancel", color = t.inkDimCompose) } },
    )

    // ---- shared building blocks (Neu aesthetic) -----------------------------------------------

    @Composable
    private fun ConfirmDialog(c: ConfirmState) = AlertDialog(
        onDismissRequest = { confirm = null },
        containerColor = t.hiCompose,
        title = { Text(c.title, color = t.inkCompose) },
        text = { Text(c.message, color = t.inkDimCompose) },
        confirmButton = { TextButton({ confirm = null; c.onConfirm() }) { Text("Reset", color = ACCENT) } },
        dismissButton = { TextButton({ confirm = null }) { Text("Cancel", color = t.inkDimCompose) } },
    )

    @Composable
    private fun Label(s: String, size: TextUnit, color: Color, bold: Boolean = false, topPad: Dp = 0.dp, bottomPad: Dp = 0.dp) =
        Text(s, Modifier.fillMaxWidth().padding(top = topPad, bottom = bottomPad), color = color,
            fontSize = size, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Text(title, Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp, start = 4.dp),
            color = ACCENT, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.14.em)
        Column(
            Modifier.fillMaxWidth().neu(t, 18.dp, NeuLevel.RAISED_SM)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            content = content,
        )
    }

    @Composable
    private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(title, 15.5.sp, t.inkCompose)
                Label(sub, 12.5.sp, t.inkDimCompose, topPad = 1.dp)
            }
            Switch(checked, onChange, colors = SwitchDefaults.colors(
                checkedThumbColor = ACCENT, uncheckedThumbColor = t.inkDimCompose,
                checkedTrackColor = ACCENT.copy(alpha = 0.4f),
                uncheckedTrackColor = t.inkDimCompose.copy(alpha = 0.25f),
                uncheckedBorderColor = Color.Transparent,
            ))
        }

    @Composable
    private fun Chip(label: String, on: Boolean, modifier: Modifier = Modifier, textSize: TextUnit = 10.5.sp,
                     hPad: Dp = 10.dp, onClick: () -> Unit) =
        Text(label,
            modifier.neu(t, 10.dp, if (on) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
                .clickable(onClick = onClick).padding(horizontal = hPad, vertical = 8.dp),
            color = if (on) t.inkCompose else t.inkDimCompose,
            fontSize = textSize, fontFamily = FontFamily.Monospace, letterSpacing = 0.04.em,
            textAlign = TextAlign.Center, maxLines = 1)

    @Composable
    private fun SmallAction(glyph: String, enabled: Boolean = true, onClick: () -> Unit) =
        Text(glyph,
            Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (enabled) t.inkDimCompose else t.inkFaintCompose.copy(alpha = 0.25f), fontSize = 13.sp)

    @Composable
    private fun NeuButton(label: String, modifier: Modifier = Modifier, filled: Boolean = true, onClick: () -> Unit) =
        Text(label,
            modifier
                .then(
                    if (filled) Modifier.background(ACCENT, RoundedCornerShape(12.dp))
                    else Modifier.border(1.dp, ACCENT.copy(alpha = 0.53f), RoundedCornerShape(12.dp))
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 11.dp),
            color = if (filled) Color(0xFFF5F2FF) else ACCENT,
            fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)

    @Composable
    private fun NeuTextField(value: String, onChange: (String) -> Unit, hint: String,
                             modifier: Modifier = Modifier, capWords: Boolean = true) =
        BasicTextField(value, onChange,
            modifier.fillMaxWidth().neu(t, 11.dp, NeuLevel.PRESSED_SM)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            singleLine = true,
            textStyle = TextStyle(color = t.inkCompose, fontSize = 14.5.sp),
            cursorBrush = SolidColor(ACCENT),
            keyboardOptions = KeyboardOptions(
                capitalization = if (capWords) KeyboardCapitalization.Words else KeyboardCapitalization.None),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(hint, color = t.inkFaintCompose, fontSize = 14.5.sp)
                    inner()
                }
            })

    @Composable
    private fun Hairline(topPad: Dp = 0.dp) =
        HorizontalDivider(Modifier.padding(top = topPad), thickness = 1.dp,
            color = t.inkFaintCompose.copy(alpha = 0.13f))
}
