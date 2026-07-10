package com.fran.teclas

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fran.teclas.MainActivity.Companion.GEMINI_API_KEY_PREF
import com.fran.teclas.MainActivity.Companion.GEMINI_DEFAULT_MODEL
import com.fran.teclas.MainActivity.Companion.GEMINI_MODEL_PREF
import com.fran.teclas.MainActivity.Companion.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * MainActivity-side host for the Travel region: the flights & boarding-passes overlay fed
 * by Gmail (via TravelRepository), the Gemini-backed flight-segment parsing, and the
 * travel card views. Bodies are moved verbatim from MainActivity and run with the
 * activity as receiver, so all view helpers/theme tokens keep resolving exactly as before.
 */
internal class TravelPaneHost(private val activity: MainActivity) {

    // ── Travel: flights & boarding passes from Gmail ─────────────────────────

    internal var travelOverlay: View? = null
    private var travelOverlayScrim: View? = null
    private var rebuildTabsAndRenderRef: (() -> Unit)? = null

    fun openTravelOverlay(startOnBoardingPasses: Boolean) { with(activity) {
        if (!requirePro(ProFeature.TRAVEL_SEARCH)) return
        if (!gmailAuth.isConfigured()) {
            Toast.makeText(this, "Add your Gmail OAuth client ID to GmailAuth.kt first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!gmailAuth.isConnected) {
            AlertDialog.Builder(this)
                .setTitle("Connect Gmail")
                .setMessage("Search your inbox for flights and boarding passes. Teclas only reads flight-related emails, on your device.")
                .setPositiveButton("Connect") { _, _ -> gmailAuth.startOAuth(this) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (travelOverlay != null) return

        val decor = window.decorView as FrameLayout
        val screenH = resources.displayMetrics.heightPixels

        val scrim = View(this).apply {
            setBackgroundColor(0xCC000000.toInt()); alpha = 0f
            setOnClickListener { dismissTravelOverlay() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF07080B.toInt())
            translationY = screenH.toFloat()
        }

        // Header with two tabs.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 24f; setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, dp(14), 0)
            setOnClickListener { haptic(this); dismissTravelOverlay() }
        })
        header.addView(TextView(this).apply {
            text = "Travel"; textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val content = FrameLayout(this)
        val loading = TextView(this).apply {
            text = "Searching your inbox…"; textSize = 13f; setTextColor(0xFF6B7280.toInt())
            gravity = Gravity.CENTER
        }
        content.addView(loading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        var currentTab = if (startOnBoardingPasses) 1 else 0
        var flightViews: List<GmailMessage> = emptyList()
        var itineraries: List<Pair<FlightSegment, GmailMessage>> = emptyList()
        var parsingFlights = false
        var passRefs: List<BoardingPassRef> = emptyList()
        var loaded = false

        fun tabButton(label: String, index: Int, onTab: () -> Unit): TextView = TextView(this).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            val active = index == currentTab
            setTextColor(if (active) 0xFF07080B.toInt() else 0xFFCED2DA.toInt())
            background = GradientDrawable().apply {
                setColor(if (active) 0xFF5FD0C4.toInt() else 0xFF161A20.toInt()); cornerRadius = dp(18).toFloat()
            }
            setOnClickListener { haptic(this); onTab() }
        }

        fun renderTab() {
            content.removeAllViews()
            if (!loaded) { content.addView(loading); return }
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(28)) }
            if (currentTab == 0) {
                when {
                    itineraries.isNotEmpty() ->
                        itineraries.forEach { (seg, msg) -> list.addView(flightSegmentCard(seg) { openEmail(msg) }) }
                    parsingFlights && flightViews.isNotEmpty() -> {
                        list.addView(travelEmpty("Reading your itineraries…"))
                        flightViews.forEach { list.addView(travelCard("✈", it.subject, travelFrom(it.from), formatTravelDate(it.date)) { openEmail(it) }) }
                    }
                    flightViews.isEmpty() -> list.addView(travelEmpty("No flight emails found in the last year."))
                    else -> flightViews.forEach { list.addView(travelCard("✈", it.subject, travelFrom(it.from), formatTravelDate(it.date)) { openEmail(it) }) }
                }
            } else {
                if (passRefs.isEmpty()) list.addView(travelEmpty("No boarding passes found in the last 60 days."))
                else passRefs.forEach { ref ->
                    val hasPass = ref.passAttachment != null
                    list.addView(travelCard(
                        if (hasPass) "🎫" else "✉",
                        ref.message.subject,
                        if (hasPass) "Tap to open pass · ${ref.passAttachment!!.filename}" else travelFrom(ref.message.from),
                        formatTravelDate(ref.message.date)
                    ) { openBoardingPass(ref) })
                }
            }
            scroll.addView(list)
            content.addView(scroll)
        }

        fun rebuildTabs() {
            tabRow.removeAllViews()
            tabRow.addView(tabButton("FLIGHTS", 0) { currentTab = 0; rebuildTabsAndRender() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            tabRow.addView(tabButton("BOARDING PASSES", 1) { currentTab = 1; rebuildTabsAndRender() })
        }

        // helper closures need to reference each other; use a holder
        fun rebuildTabsAndRenderImpl() { rebuildTabs(); renderTab() }
        rebuildTabsAndRenderRef = ::rebuildTabsAndRenderImpl
        rebuildTabs()

        panel.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(tabRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(View(this).apply { setBackgroundColor(0x12FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        panel.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        decor.addView(scrim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        decor.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (screenH * 0.92f).toInt(), Gravity.BOTTOM))
        travelOverlay = panel
        scrim.animate().alpha(1f).setDuration(200).start()
        panel.animate().translationY(0f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        travelOverlayScrim = scrim

        mediaUiScope.launch {
            val flightsD = async(Dispatchers.IO) { runCatching { travelRepo.findFlights() }.getOrDefault(emptyList()) }
            val passesD = async(Dispatchers.IO) { runCatching { travelRepo.findBoardingPasses() }.getOrDefault(emptyList()) }
            flightViews = flightsD.await()
            passRefs = passesD.await()
            loaded = true
            parsingFlights = geminiConfigured() && flightViews.isNotEmpty()
            renderTab()

            // Enrich flight emails into structured itineraries via Gemini (best-effort).
            if (parsingFlights) {
                val parsed = withContext(Dispatchers.IO) {
                    coroutineScope {
                        flightViews.map { msg ->
                            async { runCatching { fetchFlightSegments(msg) }.getOrDefault(emptyList()).map { it to msg } }
                        }.awaitAll().flatten()
                    }
                }
                if (travelOverlay != null) {
                    // Keep only future/undated segments, sorted by date when parseable.
                    itineraries = parsed
                    parsingFlights = false
                    renderTab()
                }
            }
        }
    } }

    private fun flightSegmentCard(seg: FlightSegment, onClick: () -> Unit): View { with(activity) {
        val accent = 0xFF5FD0C4.toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF12151A.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF1E2A2C.toInt())
            }
            setPadding(dp(15), dp(13), dp(15), dp(13))
            setOnClickListener { haptic(this); onClick() }
            // Top line: airline + flight number, date on the right.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = listOf(seg.airline, seg.flightNumber).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Flight" }
                    textSize = 12f; setTextColor(accent); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (seg.date.isNotBlank()) addView(TextView(context).apply {
                    text = seg.date; textSize = 11f; setTextColor(0xFF8B8F99.toInt())
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            // Route line: FROM  ✈  TO with times underneath.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                fun endpoint(code: String, time: String, alignEnd: Boolean) = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = if (alignEnd) Gravity.END else Gravity.START
                    addView(TextView(context).apply {
                        text = code.ifBlank { "—" }; textSize = 20f; setTextColor(Ink)
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    })
                    addView(TextView(context).apply { text = time; textSize = 11f; setTextColor(0xFF8B8F99.toInt()) })
                }
                addView(endpoint(seg.from, seg.depart, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = "✈"; textSize = 14f; setTextColor(0xFF6B7280.toInt()); gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(endpoint(seg.to, seg.arrive, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Footer: confirmation + seat when present.
            val footer = listOfNotNull(
                seg.confirmation.takeIf { it.isNotBlank() }?.let { "Conf $it" },
                seg.seat.takeIf { it.isNotBlank() }?.let { "Seat $it" }
            ).joinToString("   ")
            if (footer.isNotBlank()) addView(TextView(context).apply {
                text = footer; textSize = 11f; setTextColor(0xFF6B7280.toInt())
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }
    } }

    private fun rebuildTabsAndRender() { rebuildTabsAndRenderRef?.invoke() }

    fun dismissTravelOverlay() { with(activity) {
        val panel = travelOverlay ?: return
        travelOverlay = null
        val decor = window.decorView as FrameLayout
        val scrim = travelOverlayScrim
        panel.animate().translationY(resources.displayMetrics.heightPixels.toFloat()).setDuration(280)
            .withEndAction { decor.removeView(panel) }.start()
        scrim?.animate()?.alpha(0f)?.setDuration(240)?.withEndAction { decor.removeView(scrim) }?.start()
        travelOverlayScrim = null
    } }

    private fun travelEmpty(msg: String): View { with(activity) {
        return TextView(this).apply {
            text = msg; textSize = 13f; setTextColor(0xFF6B7280.toInt()); setPadding(dp(6), dp(24), dp(6), 0)
        }
    } }

    private fun travelCard(icon: String, title: String, subtitle: String, date: String, onClick: () -> Unit): View { with(activity) {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xFF12151A.toInt()); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener { haptic(this); onClick() }
            addView(TextView(context).apply { text = icon; textSize = 20f; setPadding(0, 0, dp(12), 0) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title.ifBlank { "(no subject)" }; textSize = 14f; setTextColor(Ink)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = subtitle; textSize = 11f; setTextColor(0xFF8B8F99.toInt()); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply { text = date; textSize = 10f; setTextColor(0xFF6B7280.toInt()) })
        }.also { row ->
            row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
    } }

    fun travelFrom(from: String): String {
        // "Delta Air Lines <noreply@delta.com>" → "Delta Air Lines"
        val name = from.substringBefore('<').trim().trim('"')
        return name.ifBlank { from.substringAfter('<').substringBefore('>').ifBlank { from } }
    }

    private fun formatTravelDate(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return java.text.SimpleDateFormat("MMM d", Locale.US).format(java.util.Date(epochMs))
    }

    fun openEmail(msg: GmailMessage) { with(activity) {
        // Open the message in Gmail on the web via a subject search (reliable without RFC id).
        val url = "https://mail.google.com/mail/u/0/#search/" + Uri.encode(msg.subject.ifBlank { travelFrom(msg.from) })
        startSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "No browser available")
    } }

    private fun openBoardingPass(ref: BoardingPassRef) { with(activity) {
        val pass = ref.passAttachment
        if (pass == null) { openEmail(ref.message); return }
        Toast.makeText(this, "Opening boarding pass…", Toast.LENGTH_SHORT).show()
        mediaUiScope.launch {
            val bytes = withContext(Dispatchers.IO) { gmailApi.attachmentBytes(pass.messageId, pass.attachmentId) }
            if (bytes == null) { Toast.makeText(activity, "Couldn't download the pass.", Toast.LENGTH_SHORT).show(); return@launch }
            val file = withContext(Dispatchers.IO) {
                val f = java.io.File(cacheDir, "passes").apply { mkdirs() }.let { java.io.File(it, pass.filename.ifBlank { "boardingpass" }) }
                f.writeBytes(bytes); f
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(activity, "$packageName.fileprovider", file)
            val mime = when {
                pass.filename.endsWith(".pkpass", true) || pass.mimeType.contains("pkpass", true) -> "application/vnd.apple.pkpass"
                pass.filename.endsWith(".pdf", true) || pass.mimeType == "application/pdf" -> "application/pdf"
                else -> pass.mimeType.ifBlank { "*/*" }
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startSafeIntent(view, "No app can open this pass (${mime}).")
        }
    } }

    // Extracts structured flight segments from one airline/itinerary email (Nano first, cloud fallback).
    private fun fetchFlightSegments(msg: GmailMessage): List<FlightSegment> { with(activity) {
        val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
        val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
        val emailText = (msg.subject + "\n" + msg.bodyText).take(6000)
        val prompt = """Extract every flight segment from this airline email. Reply with ONLY a JSON array — no prose.
Each element: {"airline","flightNumber","from","to","depart","arrive","date","confirmation","seat"}.
Use IATA airport codes for "from"/"to" when present (else city name). "depart"/"arrive" are local times like "4:30 PM". "date" like "Jul 12". Use "" for any unknown field. If the email contains no flight, reply exactly [].

Email:
$emailText"""
        val text = GeminiClient.generate(key, model, prompt, maxTokens = 900, temperature = 0.0, json = true)
            ?: return emptyList()
        val clean = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('['); val end = clean.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching { JSONArray(clean.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(FlightSegment(
                    airline = o.optString("airline"), flightNumber = o.optString("flightNumber"),
                    from = o.optString("from"), to = o.optString("to"),
                    depart = o.optString("depart"), arrive = o.optString("arrive"),
                    date = o.optString("date"), confirmation = o.optString("confirmation"),
                    seat = o.optString("seat")
                ))
            }
        }
    } }
}
